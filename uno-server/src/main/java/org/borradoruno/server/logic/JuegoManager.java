package org.borradoruno.server.logic;

import org.borradoruno.server.observer.PartidaPublisher;
import org.borradoruno.shared.models.*;

import java.util.List;
import java.util.UUID;

public class JuegoManager {

    private static JuegoManager instance;
    private Partida partidaActual;
    private final PartidaPublisher publisher = new PartidaPublisher();

    private JuegoManager() {
        this.partidaActual = new Partida(UUID.randomUUID().toString());
    }

    public static synchronized JuegoManager getInstance() {
        if (instance == null) {
            instance = new JuegoManager();
        }
        return instance;
    }

    public PartidaPublisher getPublisher() {
        return publisher;
    }

    public void inicializarMazo() {
        Mazo mazo = partidaActual.getMazo();
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

    public synchronized void iniciarPartida() {
        if (partidaActual.getJugadores().size() < 2) {
            return;
        }
        inicializarMazo();
        partidaActual.setEstado(EstadoPartida.EN_CURSO);
        for (Jugador j : partidaActual.getJugadores()) {
            for (int i = 0; i < 7; i++) {
                j.getMano().add(partidaActual.getMazo().robar());
            }
        }
        partidaActual.getPilaDescarte().agregarCarta(partidaActual.getMazo().robar());
        publisher.notificarCambio(partidaActual);
    }

    public synchronized void resetearPartida() {
        this.partidaActual = new Partida(UUID.randomUUID().toString());
        publisher.notificarCambio(partidaActual);
    }

    public synchronized boolean validarJugada(Jugador jugador, Carta carta) {
        int indiceJugador = partidaActual.getJugadores().indexOf(jugador);
        if (indiceJugador != partidaActual.getTurnoActual()) {
            System.out.println("Jugada rechazada: No es el turno de " + jugador.getNombre());
            return false;
        }
        PilaDescarte pila = partidaActual.getPilaDescarte();
        if (carta.getColor() == Color.NEGRO) return true;
        if (pila.getColorActivo() == Color.NEGRO) return true;
        boolean coincide = carta.getColor() == pila.getColorActivo() || carta.getValor() == pila.getValorActivo();
        if (!coincide) {
            System.out.println("Jugada rechazada: La carta " + carta.getValor() + " no coincide con la pila");
        }
        return coincide;
    }

    public synchronized void procesarJugada(Jugador jugador, Carta carta) {
        if (!validarJugada(jugador, carta)) return;
        jugador.getMano().removeIf(c -> c.getColor() == carta.getColor() && c.getValor() == carta.getValor());
        partidaActual.getPilaDescarte().agregarCarta(carta);
        if (carta.getColor() == Color.NEGRO) {
            partidaActual.getPilaDescarte().setColorActivo(Color.ROJO);
        }
        aplicarEfectos(carta);
        verificarGanador(jugador);
        avanzarTurno();
        aplicarCartasPendientes();
        publisher.notificarCambio(partidaActual);
        System.out.println("Jugada exitosa de " + jugador.getNombre()
                + ". Siguiente turno: " + partidaActual.getTurnoActual());
    }

    public synchronized void procesarJugadaComodin(Jugador jugador, Carta comodin, Color colorElegido) {
        if (!validarJugada(jugador, comodin)) return;
        jugador.getMano().removeIf(c -> c.getColor() == comodin.getColor() && c.getValor() == comodin.getValor());
        partidaActual.getPilaDescarte().agregarCarta(comodin);
        partidaActual.getPilaDescarte().setColorActivo(colorElegido);
        aplicarEfectos(comodin);
        verificarGanador(jugador);
        avanzarTurno();
        aplicarCartasPendientes();
        publisher.notificarCambio(partidaActual);
        System.out.println("Jugada exitosa de " + jugador.getNombre()
                + " con comodín. Color elegido: " + colorElegido);
    }

    private void aplicarCartasPendientes() {
        int pendientes = partidaActual.getCartasAComer();
        if (pendientes == 0) return;
        Jugador siguienteJugador = partidaActual.getJugadores().get(partidaActual.getTurnoActual());
        System.out.println(siguienteJugador.getNombre() + " debe comer " + pendientes + " cartas");
        for (int i = 0; i < pendientes; i++) {
            if (partidaActual.getMazo().getCartasRestantes() == 0) reciclarMazo();
            Carta robada = partidaActual.getMazo().robar();
            if (robada != null) siguienteJugador.getMano().add(robada);
        }
        partidaActual.setCartasAComer(0);
        avanzarTurno();
    }

    private void aplicarEfectos(Carta carta) {
        switch (carta.getValor()) {
            case REVERSA -> partidaActual.setSentidoJuego(
                    partidaActual.getSentidoJuego() == Sentido.HORARIO ? Sentido.ANTIHORARIO : Sentido.HORARIO);
            case MAS_DOS -> partidaActual.setCartasAComer(partidaActual.getCartasAComer() + 2);
            case BLOQUEO -> avanzarTurno();
            case COMODIN_MAS_CUATRO -> partidaActual.setCartasAComer(partidaActual.getCartasAComer() + 4);
            case COMODIN_COLOR -> {}
        }
    }

    private void avanzarTurno() {
        int total = partidaActual.getJugadores().size();
        int paso = partidaActual.getSentidoJuego() == Sentido.HORARIO ? 1 : -1;
        partidaActual.setTurnoActual((partidaActual.getTurnoActual() + paso + total) % total);
    }

    public synchronized void robarCarta(Jugador jugador) {
        if (partidaActual.getMazo().getCartasRestantes() == 0) reciclarMazo();
        jugador.getMano().add(partidaActual.getMazo().robar());
        avanzarTurno();
        publisher.notificarCambio(partidaActual);
    }

    private void reciclarMazo() {
        PilaDescarte pila = partidaActual.getPilaDescarte();
        List<Carta> viejas = pila.getCartas();
        Carta actual = viejas.remove(viejas.size() - 1);
        partidaActual.getMazo().getCartas().addAll(viejas);
        partidaActual.getMazo().barajar();
        viejas.clear();
        viejas.add(actual);
    }

    public Partida getPartidaActual() {
        return partidaActual;
    }

    public synchronized void agregarJugador(Jugador jugador) {
        if (partidaActual.getJugadores().isEmpty()) {
            jugador.setEsAnfitrion(true);
        }
        partidaActual.getJugadores().add(jugador);
        publisher.notificarCambio(partidaActual);
    }

    public synchronized void setMaxJugadores(int max) {
        this.partidaActual.setMaxJugadores(max);
        publisher.notificarCambio(partidaActual);
    }

    public synchronized void removerJugador(Jugador jugador) {
        boolean eraAnfitrion = jugador.isEsAnfitrion();
        partidaActual.getJugadores().remove(jugador);
        if (eraAnfitrion && !partidaActual.getJugadores().isEmpty()) {
            partidaActual.getJugadores().get(0).setEsAnfitrion(true);
        }
        publisher.notificarCambio(partidaActual);
    }

    public synchronized void marcarUno(Jugador jugador) {
        if (jugador.getMano().size() == 1) {
            jugador.setDijoUNO(true);
            System.out.println(jugador.getNombre() + " marcó UNO correctamente (tiene 1 carta)");
        } else {
            System.out.println(jugador.getNombre() + " dijo UNO pero tiene " + jugador.getMano().size() + " cartas");
        }
        publisher.notificarCambio(partidaActual);
    }

    private void verificarGanador(Jugador jugador) {
        if (jugador.getMano().isEmpty()) {
            System.out.println("¡" + jugador.getNombre() + " ha ganado la partida!");
            partidaActual.setEstado(EstadoPartida.FINALIZADA);
        }
    }
}
