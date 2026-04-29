package org.borradoruno.server.network;

import com.google.gson.Gson;
import org.borradoruno.server.controller.GameController;
import org.borradoruno.server.logic.JuegoManager;
import org.borradoruno.server.observer.PartidaObserver;
import org.borradoruno.server.observer.PartidaPublisher;
import org.borradoruno.shared.models.Jugador;
import org.borradoruno.shared.models.Partida;
import org.borradoruno.shared.network.Mensaje;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class ClientHandler implements Runnable, PartidaObserver {

    private static final int MAX_INVALID_MESSAGES = 3;
    private static final String HANDSHAKE_VALUE = "UNO-CLIENT-V1";
    private static final int SOCKET_TIMEOUT_MS = 30000;

    private final Socket socket;
    private final Server server;
    private final GameController controller;
    private final Gson gson = new Gson();
    private PrintWriter out;
    private Jugador jugador;
    private String codigoSala;

    private boolean handshakeOk = false;
    private int invalidMessageCount = 0;

    public ClientHandler(Socket socket, Server server) {
        this.socket = socket;
        this.server = server;
        this.controller = new GameController();
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.trim().isEmpty()) continue;

                if (invalidMessageCount >= MAX_INVALID_MESSAGES) {
                    System.out.println("Cliente kickeado por mensajes inválidos: " + socket.getInetAddress());
                    break;
                }

                try {
                    Mensaje mensaje = gson.fromJson(inputLine, Mensaje.class);
                    if (mensaje == null || mensaje.getTipo() == null) {
                        invalidMessageCount++;
                        continue;
                    }

                    if (!handshakeOk) {
                        if ("HANDSHAKE".equals(mensaje.getTipo())
                                && HANDSHAKE_VALUE.equals(mensaje.getDatos())) {
                            handshakeOk = true;
                            System.out.println("Handshake OK: " + socket.getInetAddress());
                        } else {
                            System.out.println("Handshake fallido desde " + socket.getInetAddress() + ", cerrando");
                            break;
                        }
                        continue;
                    }

                    invalidMessageCount = 0;
                    controller.procesarMensaje(mensaje, this);
                } catch (Exception e) {
                    invalidMessageCount++;
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println("Timeout de conexión: " + socket.getInetAddress());
        } catch (IOException e) {
            System.out.println("Cliente desconectado: " + socket.getInetAddress());
        } finally {
            if (codigoSala != null) {
                PartidaPublisher pub = JuegoManager.getInstance().getPublisher(codigoSala);
                if (pub != null) pub.desuscribir(this);
                if (jugador != null) {
                    JuegoManager.getInstance().removerJugador(codigoSala, jugador);
                }
            }
            server.removerCliente(this);
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onCambioEstado(Partida partida) {
        enviar(gson.toJson(new Mensaje("ESTADO_PARTIDA", partida)));
    }

    public void enviar(String mensajeJson) {
        if (out != null) {
            out.println(mensajeJson);
        }
    }

    public void enviarError(String mensajeError) {
        enviar(gson.toJson(new Mensaje("ERROR", mensajeError)));
    }

    public Jugador getJugador() { return jugador; }
    public void setJugador(Jugador jugador) { this.jugador = jugador; }
    public String getCodigoSala() { return codigoSala; }
    public void setCodigoSala(String codigoSala) { this.codigoSala = codigoSala; }
    public String getRemoteAddress() { return socket.getRemoteSocketAddress().toString(); }
    public Socket getSocket() { return socket; }
}
