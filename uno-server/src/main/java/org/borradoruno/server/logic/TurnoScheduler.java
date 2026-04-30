package org.borradoruno.server.logic;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TurnoScheduler {

    public static final long TIEMPO_POR_TURNO_MS = 10_000;

    private static TurnoScheduler instance;
    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledFuture<?>> tareasActivas = new ConcurrentHashMap<>();

    private TurnoScheduler() {
        this.executor = Executors.newScheduledThreadPool(2);
    }

    public static synchronized TurnoScheduler getInstance() {
        if (instance == null) {
            instance = new TurnoScheduler();
        }
        return instance;
    }

    public void iniciarTurno(String codigoSala, Runnable onTimeout) {
        cancelarTurno(codigoSala);

        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                onTimeout.run();
            } catch (Exception e) {
                System.err.println("Error en timeout del turno: " + e.getMessage());
            }
        }, TIEMPO_POR_TURNO_MS, TimeUnit.MILLISECONDS);

        tareasActivas.put(codigoSala, future);
    }

    public void cancelarTurno(String codigoSala) {
        ScheduledFuture<?> tarea = tareasActivas.remove(codigoSala);
        if (tarea != null && !tarea.isDone()) {
            tarea.cancel(false);
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
