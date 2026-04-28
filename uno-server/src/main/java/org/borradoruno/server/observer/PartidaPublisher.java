package org.borradoruno.server.observer;

import org.borradoruno.shared.models.Partida;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PartidaPublisher {
    private final List<PartidaObserver> observers = new CopyOnWriteArrayList<>();

    public void suscribir(PartidaObserver observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public void desuscribir(PartidaObserver observer) {
        observers.remove(observer);
    }

    public void notificarCambio(Partida partida) {
        System.out.println("Suscriptores activos: " + observers.size());
        for (PartidaObserver obs : observers) {
            try {
                obs.onCambioEstado(partida);
            } catch (Exception e) {
                System.err.println("Error notificando observer: " + e.getMessage());
            }
        }
    }

    public int totalObservers() {
        return observers.size();
    }
}
