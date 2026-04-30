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
    private static final int MAX_HANDSHAKE_ATTEMPTS = 3;
    private static final String HANDSHAKE_VALUE = "UNO-CLIENT-V1";
    private static final int SOCKET_TIMEOUT_MS = 60000; // TAREA 1: 60s para redes corporativas con picos de lag

    private final Socket socket;
    private final Server server;
    private final GameController controller;
    private final Gson gson = new Gson();
    private PrintWriter out;
    private Jugador jugador;
    private String codigoSala;

    private boolean handshakeOk = false;
    private int invalidMessageCount = 0;
    private int handshakeAttempts = 0; // TAREA 3

    public ClientHandler(Socket socket, Server server) {
        this.socket = socket;
        this.server = server;
        this.controller = new GameController();
    }

    @Override
    public void run() {
        try {
            // TAREA 1: timeout generoso para redes corporativas
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            // TAREA 4: configuración de socket para redes con paquetes fragmentados
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setReceiveBufferSize(32768);
            socket.setSendBufferSize(32768);

            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.trim().isEmpty()) continue;

                if (invalidMessageCount >= MAX_INVALID_MESSAGES) {
                    System.out.println("Cliente kickeado por mensajes inválidos: " + socket.getInetAddress());
                    break;
                }

                // TAREA 2+3: bloque pre-handshake con logging detallado y tolerancia a basura de proxy
                if (!handshakeOk) {
                    System.out.println("[Raw] Bytes recibidos de " + socket.getInetAddress()
                            + ": [" + inputLine + "]");

                    // Limpiar BOM y whitespace que pueden inyectar proxies
                    String linea = inputLine.replace("﻿", "").trim();

                    try {
                        Mensaje msg = gson.fromJson(linea, Mensaje.class);

                        // TAREA 6: PING_TEST no requiere handshake y no consume intentos
                        if (msg != null && "PING_TEST".equals(msg.getTipo())) {
                            enviar(gson.toJson(new Mensaje("PONG_TEST", "Servidor OK")));
                            System.out.println("[Diagnóstico] PING_TEST recibido de " + socket.getInetAddress());
                            continue;
                        }

                        handshakeAttempts++;
                        System.out.println("[Handshake] Recibido de " + socket.getInetAddress()
                                + " (intento " + handshakeAttempts + "/" + MAX_HANDSHAKE_ATTEMPTS + ")"
                                + ": [" + linea + "]");

                        if (msg != null && "HANDSHAKE".equals(msg.getTipo())
                                && HANDSHAKE_VALUE.equals(msg.getDatos())) {
                            handshakeOk = true;
                            System.out.println("[Handshake] OK: " + socket.getInetAddress());
                            continue;
                        }
                    } catch (Exception e) {
                        handshakeAttempts++;
                        System.out.println("[Handshake] JSON inválido: " + e.getMessage()
                                + " (intento " + handshakeAttempts + "/" + MAX_HANDSHAKE_ATTEMPTS + ")");
                    }

                    if (handshakeAttempts >= MAX_HANDSHAKE_ATTEMPTS) {
                        System.out.println("[Handshake] FALLIDO definitivamente desde "
                                + socket.getInetAddress() + " tras " + MAX_HANDSHAKE_ATTEMPTS + " intentos, cerrando");
                        break;
                    }
                    System.out.println("[Handshake] Intento fallido, esperando siguiente mensaje...");
                    continue;
                }

                // Flujo normal post-handshake
                try {
                    Mensaje mensaje = gson.fromJson(inputLine, Mensaje.class);
                    if (mensaje == null || mensaje.getTipo() == null) {
                        invalidMessageCount++;
                        continue;
                    }

                    // TAREA 6: PING_TEST disponible también post-handshake
                    if ("PING_TEST".equals(mensaje.getTipo())) {
                        enviar(gson.toJson(new Mensaje("PONG_TEST", "Servidor OK")));
                        System.out.println("[Diagnóstico] PING_TEST recibido de " + socket.getInetAddress());
                        continue;
                    }

                    invalidMessageCount = 0;
                    controller.procesarMensaje(mensaje, this);
                } catch (Exception e) {
                    invalidMessageCount++;
                }
            }
        } catch (SocketTimeoutException e) {
            // TAREA 5
            System.out.println("[Conexión] Timeout: " + socket.getInetAddress()
                    + " (sin actividad por " + (SOCKET_TIMEOUT_MS / 1000) + "s)");
        } catch (java.net.SocketException e) {
            // TAREA 5: Connection reset, broken pipe, etc.
            System.out.println("[Conexión] Reset/cerrada por cliente: " + socket.getInetAddress()
                    + " - " + e.getMessage());
        } catch (IOException e) {
            System.out.println("[Conexión] Error de I/O: " + socket.getInetAddress()
                    + " - " + e.getMessage());
        } finally {
            limpiarConexion();
        }
    }

    private void limpiarConexion() {
        if (codigoSala != null && jugador != null) {
            try {
                JuegoManager.getInstance().removerJugador(codigoSala, jugador);
                System.out.println("Jugador " + jugador.getNombre() + " removido de sala " + codigoSala);

                Partida sala = JuegoManager.getInstance().getPartida(codigoSala);
                if (sala != null && sala.getJugadores().isEmpty()) {
                    JuegoManager.getInstance().eliminarSala(codigoSala);
                }
            } catch (Exception e) {
                System.err.println("Error limpiando jugador: " + e.getMessage());
            }
        }

        if (codigoSala != null) {
            PartidaPublisher pub = JuegoManager.getInstance().getPublisher(codigoSala);
            if (pub != null) pub.desuscribir(this);
        }

        server.removerCliente(this);

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // ignore
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
