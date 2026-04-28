package org.borradoruno.server.network;

import com.google.gson.Gson;
import org.borradoruno.server.controller.GameController;
import org.borradoruno.server.logic.JuegoManager;
import org.borradoruno.server.observer.PartidaObserver;
import org.borradoruno.shared.models.Jugador;
import org.borradoruno.shared.models.Partida;
import org.borradoruno.shared.network.Mensaje;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientHandler implements Runnable, PartidaObserver {

    private final Socket socket;
    private final Server server;
    private final GameController controller;
    private final Gson gson = new Gson();
    private PrintWriter out;
    private Jugador jugador;

    public ClientHandler(Socket socket, Server server) {
        this.socket = socket;
        this.server = server;
        this.controller = new GameController();
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            JuegoManager.getInstance().getPublisher().suscribir(this);

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.trim().isEmpty()) continue;
                try {
                    Mensaje mensaje = gson.fromJson(inputLine, Mensaje.class);
                    if (mensaje == null || mensaje.getTipo() == null) {
                        enviarError("Mensaje inválido");
                        continue;
                    }
                    controller.procesarMensaje(mensaje, this);
                } catch (Exception e) {
                    System.err.println("Error parseando JSON: " + e.getMessage());
                    enviarError("Error de formato JSON");
                }
            }
        } catch (IOException e) {
            System.out.println("Cliente desconectado");
        } finally {
            JuegoManager.getInstance().getPublisher().desuscribir(this);
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

    public Jugador getJugador() {
        return jugador;
    }

    public void setJugador(Jugador jugador) {
        this.jugador = jugador;
    }

    public String getRemoteAddress() {
        return socket.getRemoteSocketAddress().toString();
    }
}
