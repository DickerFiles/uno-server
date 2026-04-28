package org.borradoruno.server.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import org.borradoruno.server.logic.JuegoManager;
import org.borradoruno.server.network.ClientHandler;
import org.borradoruno.server.validation.GameStateValidator;
import org.borradoruno.server.validation.InputValidator;
import org.borradoruno.server.validation.ValidationResult;
import org.borradoruno.shared.models.*;
import org.borradoruno.shared.network.Mensaje;

import java.net.Socket;

public class GameController {

    private final Gson gson = new Gson();

    public void procesarMensaje(Mensaje mensaje, ClientHandler handler) {
        String tipo = mensaje.getTipo();
        System.out.println("Comando recibido: " + tipo + " con datos: " + mensaje.getDatos());

        boolean requiereJugador = !tipo.equals("CREATE")
                && !tipo.equals("JOIN")
                && !tipo.equals("SOLICITAR_ESTADO");

        if (requiereJugador && handler.getJugador() == null) {
            handler.enviarError("Debes unirte a una sala primero");
            return;
        }

        try {
            switch (tipo) {
                case "CREATE" -> manejarCreate(mensaje, handler);
                case "JOIN" -> manejarJoin(mensaje, handler);
                case "SET_MAX_JUGADORES" -> manejarSetMax(mensaje, handler);
                case "INICIAR_PARTIDA" -> manejarIniciarPartida(handler);
                case "TIRAR_CARTA" -> manejarTirarCarta(mensaje, handler);
                case "TIRAR_COMODIN" -> manejarTirarComodin(mensaje, handler);
                case "ROBAR_CARTA" -> manejarRobar(handler);
                case "DECIR_UNO" -> manejarDecirUno(handler);
                case "ABANDONAR_SALA" -> manejarAbandonar(handler);
                case "SOLICITAR_ESTADO" -> manejarSolicitarEstado(handler);
                default -> handler.enviarError("Comando desconocido: " + tipo);
            }
        } catch (Exception e) {
            System.err.println("Error procesando mensaje: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void manejarCreate(Mensaje mensaje, ClientHandler handler) {
        if (mensaje.getDatos() == null) {
            handler.enviarError("El nombre no puede ser null");
            return;
        }
        String nombre = (String) mensaje.getDatos();
        ValidationResult nicknameResult = InputValidator.validateNickname(nombre);
        if (!nicknameResult.isValid()) {
            handler.enviarError(nicknameResult.getErrorMessage());
            return;
        }
        if (isNicknameTaken(nombre)) {
            handler.enviarError("El apodo '" + nombre + "' ya está en uso");
            return;
        }
        if (JuegoManager.getInstance().getPartidaActual().getJugadores().isEmpty()) {
            JuegoManager.getInstance().resetearPartida();
        }
        Jugador jugador = new Jugador(nombre, handler.getRemoteAddress());
        handler.setJugador(jugador);
        JuegoManager.getInstance().agregarJugador(jugador);
    }

    private void manejarJoin(Mensaje mensaje, ClientHandler handler) {
        if (mensaje.getDatos() == null) {
            handler.enviarError("El nombre no puede ser null");
            return;
        }
        String nombre = (String) mensaje.getDatos();
        ValidationResult nicknameResult = InputValidator.validateNickname(nombre);
        if (!nicknameResult.isValid()) {
            handler.enviarError(nicknameResult.getErrorMessage());
            return;
        }
        if (isNicknameTaken(nombre)) {
            handler.enviarError("El apodo '" + nombre + "' ya está en uso");
            return;
        }
        Partida p = JuegoManager.getInstance().getPartidaActual();
        if (p.getJugadores().size() >= p.getMaxJugadores()) {
            handler.enviarError("La sala está llena");
            return;
        }
        Jugador jugador = new Jugador(nombre, handler.getRemoteAddress());
        handler.setJugador(jugador);
        JuegoManager.getInstance().agregarJugador(jugador);
    }

    private void manejarSetMax(Mensaje mensaje, ClientHandler handler) {
        if (mensaje.getDatos() == null) {
            handler.enviarError("El valor de max jugadores no puede ser null");
            return;
        }
        int max;
        try {
            if (mensaje.getDatos() instanceof Double) {
                max = ((Double) mensaje.getDatos()).intValue();
            } else if (mensaje.getDatos() instanceof Integer) {
                max = (Integer) mensaje.getDatos();
            } else {
                handler.enviarError("Formato de max jugadores inválido");
                return;
            }
        } catch (Exception e) {
            handler.enviarError("Error parseando max jugadores: " + e.getMessage());
            return;
        }
        ValidationResult maxResult = InputValidator.validateMaxPlayers(max);
        if (!maxResult.isValid()) {
            handler.enviarError(maxResult.getErrorMessage());
            return;
        }
        System.out.println("Nuevo límite de jugadores: " + max);
        JuegoManager.getInstance().setMaxJugadores(max);
    }

    private void manejarIniciarPartida(ClientHandler handler) {
        ValidationResult minPlayers = GameStateValidator.validateMinPlayers(
                JuegoManager.getInstance().getPartidaActual(), 2);
        if (!minPlayers.isValid()) {
            handler.enviarError(minPlayers.getErrorMessage());
            return;
        }
        JuegoManager.getInstance().iniciarPartida();
    }

    private void manejarTirarCarta(Mensaje mensaje, ClientHandler handler) {
        String cJson = gson.toJson(mensaje.getDatos());
        Carta cartaTirada = gson.fromJson(cJson, Carta.class);
        ValidationResult inHand = GameStateValidator.validateCardInHand(handler.getJugador(), cartaTirada);
        if (!inHand.isValid()) {
            handler.enviarError(inHand.getErrorMessage());
            return;
        }
        JuegoManager.getInstance().procesarJugada(handler.getJugador(), cartaTirada);
    }

    private void manejarTirarComodin(Mensaje mensaje, ClientHandler handler) {
        try {
            JsonArray arr = gson.toJsonTree(mensaje.getDatos()).getAsJsonArray();
            if (arr == null || arr.size() != 2) {
                handler.enviarError("Formato de comodín inválido (se esperan 2 elementos)");
                return;
            }
            Carta comodin = gson.fromJson(arr.get(0), Carta.class);
            String colorStr = arr.get(1).getAsString();
            ValidationResult colorResult = InputValidator.validateColor(colorStr);
            if (!colorResult.isValid()) {
                handler.enviarError(colorResult.getErrorMessage());
                return;
            }
            Color colorElegido = Color.valueOf(colorStr.toUpperCase());
            JuegoManager.getInstance().procesarJugadaComodin(handler.getJugador(), comodin, colorElegido);
        } catch (Exception e) {
            System.err.println("Error procesando comodín: " + e.getMessage());
            handler.enviarError("Error procesando comodín: " + e.getMessage());
        }
    }

    private void manejarRobar(ClientHandler handler) {
        JuegoManager.getInstance().robarCarta(handler.getJugador());
    }

    private void manejarDecirUno(ClientHandler handler) {
        JuegoManager.getInstance().marcarUno(handler.getJugador());
        System.out.println(handler.getJugador().getNombre() + " dijo UNO!");
    }

    private void manejarAbandonar(ClientHandler handler) {
        JuegoManager.getInstance().removerJugador(handler.getJugador());
        handler.setJugador(null);
    }

    private void manejarSolicitarEstado(ClientHandler handler) {
        handler.enviar(gson.toJson(new Mensaje("ESTADO_PARTIDA",
                JuegoManager.getInstance().getPartidaActual())));
    }

    private boolean isNicknameTaken(String nombre) {
        Partida partida = JuegoManager.getInstance().getPartidaActual();
        if (partida == null || partida.getJugadores() == null) return false;
        return partida.getJugadores().stream().anyMatch(j -> j.getNombre().equalsIgnoreCase(nombre));
    }
}
