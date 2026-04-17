package org.itson.uno.servidor;

import java.io.*;
import java.net.*;

/**
 * clase base de conexion al servidor UNO ONLINE
 *
 * @author gatog
 */
public class UnoServidor {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "5050"));
        System.out.println("Servidor escuchando en puerto " + port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client)).start();
            }
        }
    }

    private static void handleClient(Socket client) {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream())); PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {
            System.out.println("Cliente conectado: " + client.getInetAddress());
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("Recibido: " + line);
                if (line.trim().equalsIgnoreCase("ping")) {
                    out.println("pong");
                } else {
                    out.println("Comando desconocido. Envía: ping");
                }
            }
        } catch (IOException e) {
            System.out.println("Cliente desconectado.");
        }
    }

}
