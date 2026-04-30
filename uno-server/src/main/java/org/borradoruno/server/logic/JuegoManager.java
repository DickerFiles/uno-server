package org.borradoruno.server.logic;

import org.borradoruno.server.observer.PartidaPublisher;
import org.borradoruno.shared.models.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JuegoManager {

    private static JuegoManager instance;
    private final Map<String, Partida> partidas = new ConcurrentHashMap<>();
    private final Map<String, PartidaPublisher> publishers = new ConcurrentHashMap<>();

    private JuegoManager() {}

    public static synchronized JuegoManager getInstance() {
        if (instance == null) {
            instance = new JuegoManager();
        }
        return instance;
    }

    public synchronized Partida crearSala() {
        Partida partida = new Partida(UUID.randomUUID().toString());
        partidas.put(partida.getCodigoSala(), partida);
        publishers.put(partida.getCodigoSala(), new PartidaPublisher());
        System.out.println("Sala creada: " + partida.getCodigoSala());
        return partida;
    }

    public Partida getPartida(String codigoSala) {
        return partidas.get(codigoSala);
    }

    public PartidaPublisher getPublisher(String codigoSala) {
        return publishers.get(codigoSala);
    }

    public synchronized void eliminarSala(String codigoSala) {
        partidas.remove(codigoSala);
        publishers.remove(codigoSala);
        System.out.println("Sala eliminada: " + codigoSala);
    }

    private void inicializarMazo(Partida partida) {
        Mazo mazo = partida.getMazo();
        mazo.getCartas().clear();
        for (Color c : Color.values()) {
            if (c == Color.NEGRO) continue;
            for (Valor v : Valor.values()) {
                if (v == Valor.COMODIN_COLOR || v == Valor.COMODIN_MAS_CUATRO) continue;
                mazo.getCartas().add(new Carta(c, v, false, 0));
                if (v != Valor.CERO) {
                    mazo.getCartas().add(new Carta(c, v, false, 0));
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            mazo.getCartas().add(new Carta(Color.NEGRO, Valor.COMODIN_COLOR, true, 50));
            mazo.getCartas().add(new Carta(Color.NEGRO, Valor.COMODIN_MAS_CUATRO, true, 50));
        }
        mazo.barajar();
    }

    public synchronized boolean iniciarPartida(String codigoSala) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return false;
        if (partida.getJugadores().size() < 2) return false;

        boolean todosListos = partida.getJugadores().stream()
                .filter(j -> !j.isEsAnfitrion())
                .allMatch(Jugador::isListo);
        if (!todosListos) return false;

        inicializarMazo(partida);
        partida.setEstado(EstadoPartida.EN_CURSO);
        for (Jugador j : partida.getJugadores()) {
            for (int i = 0; i < 7; i++) {
                j.getMano().add(partida.getMazo().robar());
            }
        }
        partida.getPilaDescarte().agregarCarta(partida.getMazo().robar());
        publishers.get(codigoSala).notificarCambio(partida);
        return true;
    }

    public synchronized boolean validarJugada(String codigoSala, Jugador jugador, Carta carta) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return false;
        int indiceJugador = partida.getJugadores().indexOf(jugador);
        if (indiceJugador != partida.getTurnoActual()) {
            System.out.println("Jugada rechazada: No es el turno de " + jugador.getNombre());
            return false;
        }
        PilaDescarte pila = partida.getPilaDescarte();
        if (carta.getColor() == Color.NEGRO) return true;
        if (pila.getColorActivo() == Color.NEGRO) return true;
        boolean coincide = carta.getColor() == pila.getColorActivo() || carta.getValor() == pila.getValorActivo();
        if (!coincide) {
            System.out.println("Jugada rechazada: La carta " + carta.getValor() + " no coincide con la pila");
        }
        return coincide;
    }

    public synchronized void procesarJugada(String codigoSala, Jugador jugador, Carta carta) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return;
        if (!validarJugada(codigoSala, jugador, carta)) return;
        jugador.getMano().removeIf(c -> c.getColor() == carta.getColor() && c.getValor() == carta.getValor());
        partida.getPilaDescarte().agregarCarta(carta);
        if (carta.getColor() == Color.NEGRO) {
            partida.getPilaDescarte().setColorActivo(Color.ROJO);
        }
        aplicarEfectos(partida, carta);
        verificarGanador(partida, jugador);
        avanzarTurno(partida);
        aplicarCartasPendientes(codigoSala, partida);
        publishers.get(codigoSala).notificarCambio(partida);
        System.out.println("Jugada de " + jugador.getNombre() + ". Turno: " + partida.getTurnoActual());
    }

    public synchronized void procesarJugadaComodin(String codigoSala, Jugador jugador, Carta comodin, Color colorElegido) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return;
        if (!validarJugada(codigoSala, jugador, comodin)) return;
        jugador.getMano().removeIf(c -> c.getColor() == comodin.getColor() && c.getValor() == comodin.getValor());
        partida.getPilaDescarte().agregarCarta(comodin);
        partida.getPilaDescarte().setColorActivo(colorElegido);
        aplicarEfectos(partida, comodin);
        verificarGanador(partida, jugador);
        avanzarTurno(partida);
        aplicarCartasPendientes(codigoSala, partida);
        publishers.get(codigoSala).notificarCambio(partida);
        System.out.println("Comodín de " + jugador.getNombre() + ". Color: " + colorElegido);
    }

    private void aplicarCartasPendientes(String codigoSala, Partida partida) {
        int pendientes = partida.getCartasAComer();
        if (pendientes == 0) return;
        Jugador siguiente = partida.getJugadores().get(partida.getTurnoActual());
        System.out.println(siguiente.getNombre() + " debe comer " + pendientes + " cartas");
        for (int i = 0; i < pendientes; i++) {
            if (partida.getMazo().getCartasRestantes() == 0) reciclarMazo(partida);
            Carta robada = partida.getMazo().robar();
            if (robada != null) siguiente.getMano().add(robada);
        }
        partida.setCartasAComer(0);
        avanzarTurno(partida);
    }

    private void aplicarEfectos(Partida partida, Carta carta) {
        switch (carta.getValor()) {
            case REVERSA -> partida.setSentidoJuego(
                    partida.getSentidoJuego() == Sentido.HORARIO ? Sentido.ANTIHORARIO : Sentido.HORARIO);
            case MAS_DOS -> partida.setCartasAComer(partida.getCartasAComer() + 2);
            case BLOQUEO -> avanzarTurno(partida);
            case COMODIN_MAS_CUATRO -> partida.setCartasAComer(partida.getCartasAComer() + 4);
            case COMODIN_COLOR -> {}
        }
    }

    private void avanzarTurno(Partida partida) {
        int total = partida.getJugadores().size();
        int paso = partida.getSentidoJuego() == Sentido.HORARIO ? 1 : -1;
        partida.setTurnoActual((partida.getTurnoActual() + paso + total) % total);
    }

    public synchronized boolean robarCarta(String codigoSala, Jugador jugador) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return false;

        int indiceJugador = partida.getJugadores().indexOf(jugador);
        if (indiceJugador != partida.getTurnoActual()) {
            System.out.println("Robar rechazado: no es el turno de " + jugador.getNombre());
            return false;
        }

        if (partida.getMazo().getCartasRestantes() == 0) reciclarMazo(partida);
        Carta carta = partida.getMazo().robar();
        if (carta != null) {
            jugador.getMano().add(carta);
            System.out.println(jugador.getNombre() + " robó 1 carta");
        }

        avanzarTurno(partida);
        publishers.get(codigoSala).notificarCambio(partida);
        return true;
    }

    private void reciclarMazo(Partida partida) {
        PilaDescarte pila = partida.getPilaDescarte();
        List<Carta> viejas = pila.getCartas();
        Carta actual = viejas.remove(viejas.size() - 1);
        partida.getMazo().getCartas().addAll(viejas);
        partida.getMazo().barajar();
        viejas.clear();
        viejas.add(actual);
    }

    public synchronized void agregarJugador(String codigoSala, Jugador jugador) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return;
        if (partida.getJugadores().isEmpty()) {
            jugador.setEsAnfitrion(true);
        }
        partida.getJugadores().add(jugador);
        publishers.get(codigoSala).notificarCambio(partida);
    }

    public synchronized void setMaxJugadores(String codigoSala, int max) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return;
        partida.setMaxJugadores(max);
        publishers.get(codigoSala).notificarCambio(partida);
    }

    public synchronized void removerJugador(String codigoSala, Jugador jugador) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return;
        boolean eraAnfitrion = jugador.isEsAnfitrion();
        partida.getJugadores().remove(jugador);
        if (partida.getJugadores().isEmpty()) {
            eliminarSala(codigoSala);
            return;
        }
        if (eraAnfitrion) {
            partida.getJugadores().get(0).setEsAnfitrion(true);
        }
        publishers.get(codigoSala).notificarCambio(partida);
    }

    public synchronized void marcarUno(String codigoSala, Jugador jugador) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return;
        if (jugador.getMano().size() == 1) {
            jugador.setDijoUNO(true);
            System.out.println(jugador.getNombre() + " marcó UNO correctamente");
        } else {
            System.out.println(jugador.getNombre() + " dijo UNO pero tiene " + jugador.getMano().size() + " cartas");
        }
        publishers.get(codigoSala).notificarCambio(partida);
    }

    public synchronized void marcarListo(String codigoSala, Jugador jugador) {
        Partida partida = partidas.get(codigoSala);
        if (partida == null) return;
        jugador.setListo(!jugador.isListo());
        System.out.println(jugador.getNombre() + " está " + (jugador.isListo() ? "listo" : "no listo"));
        publishers.get(codigoSala).notificarCambio(partida);
    }

    private void verificarGanador(Partida partida, Jugador jugador) {
        if (jugador.getMano().isEmpty()) {
            System.out.println("¡" + jugador.getNombre() + " ha ganado!");
            partida.setEstado(EstadoPartida.FINALIZADA);
        }
    }
}
