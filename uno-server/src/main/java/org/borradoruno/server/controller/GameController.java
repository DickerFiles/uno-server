package org.borradoruno.server.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import org.borradoruno.server.logic.JuegoManager;
import org.borradoruno.server.network.ClientHandler;
import org.borradoruno.server.observer.PartidaPublisher;
import org.borradoruno.server.validation.GameStateValidator;
import org.borradoruno.server.validation.InputValidator;
import org.borradoruno.server.validation.ValidationResult;
import org.borradoruno.shared.models.*;
import org.borradoruno.shared.models.Avatar;
import org.borradoruno.shared.network.Mensaje;

public class GameController {

    private final Gson gson = new Gson();

    public void procesarMensaje(Mensaje mensaje, ClientHandler handler) {
        String tipo = mensaje.getTipo();
        System.out.println("Comando: " + tipo + " de " + handler.getRemoteAddress());

        boolean requiereJugador = !tipo.equals("CREATE")
                && !tipo.equals("JOIN")
                && !tipo.equals("SOLICITAR_ESTADO");

        if (requiereJugador && handler.getJugador() == null) {
            handler.enviarError("Debes unirte a una sala primero");
            return;
        }

        try {
            switch (tipo) {
                case "CREATE"            -> manejarCreate(mensaje, handler);
                case "JOIN"              -> manejarJoin(mensaje, handler);
                case "SET_MAX_JUGADORES" -> manejarSetMax(mensaje, handler);
                case "INICIAR_PARTIDA"   -> manejarIniciarPartida(handler);
                case "MARCAR_LISTO"      -> manejarMarcarListo(handler);
                case "TIRAR_CARTA"       -> manejarTirarCarta(mensaje, handler);
                case "TIRAR_COMODIN"     -> manejarTirarComodin(mensaje, handler);
                case "ROBAR_CARTA"       -> manejarRobar(handler);
                case "PASAR_TURNO"       -> manejarPasarTurno(handler);
                case "DECIR_UNO"         -> manejarDecirUno(handler);
                case "ABANDONAR_SALA"    -> manejarAbandonar(handler);
                case "SOLICITAR_ESTADO"  -> manejarSolicitarEstado(handler);
                default -> handler.enviarError("Comando desconocido: " + tipo);
            }
        } catch (Exception e) {
            System.err.println("Error procesando mensaje: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void manejarCreate(Mensaje mensaje, ClientHandler handler) {
        if (mensaje.getDatos() == null) {
            handler.enviarError("CREATE requiere datos");
            return;
        }

        JsonArray arr;
        try {
            arr = gson.toJsonTree(mensaje.getDatos()).getAsJsonArray();
        } catch (Exception e) {
            handler.enviarError("CREATE requiere [nombre, avatar]");
            return;
        }

        if (arr.size() != 2) {
            handler.enviarError("CREATE requiere exactamente 2 elementos: [nombre, avatar]");
            return;
        }

        String nombre = arr.get(0).getAsString();
        String avatarStr = arr.get(1).getAsString();

        ValidationResult nicknameResult = InputValidator.validateNickname(nombre);
        if (!nicknameResult.isValid()) {
            handler.enviarError(nicknameResult.getErrorMessage());
            return;
        }

        Avatar avatar;
        try {
            avatar = Avatar.valueOf(avatarStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            avatar = Avatar.AZUL;
        }

        Partida nuevaSala = JuegoManager.getInstance().crearSala();
        Jugador jugador = new Jugador(nombre, handler.getRemoteAddress());
        jugador.setEsAnfitrion(true);
        jugador.setAvatar(avatar);

        handler.setJugador(jugador);
        handler.setCodigoSala(nuevaSala.getCodigoSala());
        JuegoManager.getInstance().getPublisher(nuevaSala.getCodigoSala()).suscribir(handler);
        JuegoManager.getInstance().agregarJugador(nuevaSala.getCodigoSala(), jugador);
    }

    private void manejarJoin(Mensaje mensaje, ClientHandler handler) {
        if (mensaje.getDatos() == null) {
            handler.enviarError("JOIN requiere [nombre, codigoSala, avatar]");
            return;
        }
        JsonArray arr;
        try {
            arr = gson.toJsonTree(mensaje.getDatos()).getAsJsonArray();
        } catch (Exception e) {
            handler.enviarError("JOIN requiere [nombre, codigoSala, avatar]");
            return;
        }
        if (arr.size() != 3) {
            handler.enviarError("JOIN requiere exactamente 3 elementos: [nombre, codigoSala, avatar]");
            return;
        }

        String nombre = arr.get(0).getAsString();
        String codigoSala = arr.get(1).getAsString();
        String avatarStr = arr.get(2).getAsString();

        ValidationResult nicknameResult = InputValidator.validateNickname(nombre);
        if (!nicknameResult.isValid()) {
            handler.enviarError(nicknameResult.getErrorMessage());
            return;
        }

        Partida sala = JuegoManager.getInstance().getPartida(codigoSala);
        if (sala == null) {
            handler.enviarError("Sala '" + codigoSala + "' no existe");
            return;
        }
        if (sala.getEstado() != EstadoPartida.ESPERANDO_JUGADORES) {
            handler.enviarError("La partida ya inició");
            return;
        }
        if (sala.getJugadores().size() >= sala.getMaxJugadores()) {
            handler.enviarError("La sala está llena");
            return;
        }
        if (isNicknameTaken(codigoSala, nombre)) {
            handler.enviarError("El apodo '" + nombre + "' ya está en uso en esta sala");
            return;
        }

        Avatar avatar;
        try {
            avatar = Avatar.valueOf(avatarStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            avatar = Avatar.AZUL;
        }

        Jugador jugador = new Jugador(nombre, handler.getRemoteAddress());
        jugador.setAvatar(avatar);
        handler.setJugador(jugador);
        handler.setCodigoSala(codigoSala);
        JuegoManager.getInstance().getPublisher(codigoSala).suscribir(handler);
        JuegoManager.getInstance().agregarJugador(codigoSala, jugador);
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
        JuegoManager.getInstance().setMaxJugadores(handler.getCodigoSala(), max);
    }

    private void manejarIniciarPartida(ClientHandler handler) {
        if (!handler.getJugador().isEsAnfitrion()) {
            handler.enviarError("Solo el anfitrión puede iniciar la partida");
            return;
        }
        String codigo = handler.getCodigoSala();
        Partida partida = JuegoManager.getInstance().getPartida(codigo);
        if (partida == null) {
            handler.enviarError("Sala no encontrada");
            return;
        }
        ValidationResult minPlayers = GameStateValidator.validateMinPlayers(partida, 2);
        if (!minPlayers.isValid()) {
            handler.enviarError(minPlayers.getErrorMessage());
            return;
        }
        boolean iniciada = JuegoManager.getInstance().iniciarPartida(codigo);
        if (!iniciada) {
            handler.enviarError("No se puede iniciar: faltan jugadores que marquen 'Listo'");
        }
    }

    private void manejarMarcarListo(ClientHandler handler) {
        JuegoManager.getInstance().marcarListo(handler.getCodigoSala(), handler.getJugador());
    }

    private void manejarTirarCarta(Mensaje mensaje, ClientHandler handler) {
        String cJson = gson.toJson(mensaje.getDatos());
        Carta cartaTirada = gson.fromJson(cJson, Carta.class);
        ValidationResult inHand = GameStateValidator.validateCardInHand(handler.getJugador(), cartaTirada);
        if (!inHand.isValid()) {
            handler.enviarError(inHand.getErrorMessage());
            return;
        }
        JuegoManager.getInstance().procesarJugada(handler.getCodigoSala(), handler.getJugador(), cartaTirada);
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
            JuegoManager.getInstance().procesarJugadaComodin(
                    handler.getCodigoSala(), handler.getJugador(), comodin, colorElegido);
        } catch (Exception e) {
            System.err.println("Error procesando comodín: " + e.getMessage());
            handler.enviarError("Error procesando comodín: " + e.getMessage());
        }
    }

    private void manejarRobar(ClientHandler handler) {
        boolean ok = JuegoManager.getInstance().robarCarta(handler.getCodigoSala(), handler.getJugador());
        if (!ok) handler.enviarError("No es tu turno para robar");
    }

    private void manejarPasarTurno(ClientHandler handler) {
        boolean ok = JuegoManager.getInstance().pasarTurno(handler.getCodigoSala(), handler.getJugador());
        if (!ok) handler.enviarError("No puedes pasar el turno ahora");
    }

    private void manejarDecirUno(ClientHandler handler) {
        JuegoManager.getInstance().marcarUno(handler.getCodigoSala(), handler.getJugador());
        System.out.println(handler.getJugador().getNombre() + " dijo UNO!");
    }

    private void manejarAbandonar(ClientHandler handler) {
        String codigo = handler.getCodigoSala();
        if (codigo != null && handler.getJugador() != null) {
            PartidaPublisher pub = JuegoManager.getInstance().getPublisher(codigo);
            if (pub != null) pub.desuscribir(handler);
            JuegoManager.getInstance().removerJugador(codigo, handler.getJugador());
        }
        handler.setJugador(null);
        handler.setCodigoSala(null);
    }

    private void manejarSolicitarEstado(ClientHandler handler) {
        String codigo = handler.getCodigoSala();
        Partida partida = codigo != null ? JuegoManager.getInstance().getPartida(codigo) : null;
        handler.enviar(gson.toJson(new Mensaje("ESTADO_PARTIDA", partida)));
    }

    private boolean isNicknameTaken(String codigoSala, String nombre) {
        Partida partida = JuegoManager.getInstance().getPartida(codigoSala);
        if (partida == null || partida.getJugadores() == null) return false;
        return partida.getJugadores().stream().anyMatch(j -> j.getNombre().equalsIgnoreCase(nombre));
    }
}
