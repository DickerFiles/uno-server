package org.borradoruno.server.observer;

import org.borradoruno.shared.models.Partida;

public interface PartidaObserver {
    void onCambioEstado(Partida partida);
}
