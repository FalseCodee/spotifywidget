package me.falsecode.spotifywidget;

import com.sun.net.httpserver.HttpServer;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRefreshRequest;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRequest;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;

import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

public class AuthorizationManager {
    private static final URI redirectUri = SpotifyHttpManager.makeUri("http://127.0.0.1:6123");
    private static long accessTokenExpireDate = 0;

    private static SpotifyApi spotifyApi;
    private static final AuthorizationCodeUriRequest authorizationCodeUriRequest = spotifyApi.authorizationCodeUri()
            .state("x4xkmn9pu3j6ukrs8n")
            .scope("user-read-playback-state")
            .show_dialog(true)
            .build();

    private static CompletableFuture<URI> getAuthorizationCodeUriAsync() {
       return authorizationCodeUriRequest.executeAsync();
    }

    private static void processAuthorizationCodeAsync(String code) {
        System.out.println(code);
        AuthorizationCodeRequest authorizationCodeRequest = spotifyApi.authorizationCode(code)
                .build();
        CompletableFuture<AuthorizationCodeCredentials> credentialsFuture = authorizationCodeRequest.executeAsync();
        credentialsFuture.whenComplete((authorizationCodeCredentials, throwable) ->  {
            spotifyApi.setAccessToken(authorizationCodeCredentials.getAccessToken());
            spotifyApi.setRefreshToken(authorizationCodeCredentials.getRefreshToken());
            accessTokenExpireDate = System.currentTimeMillis() + (authorizationCodeCredentials.getExpiresIn() * 1000);
            File file = new File("refresh_token.txt");
            try(FileWriter writer = new FileWriter(file)) {
                writer.write(spotifyApi.getRefreshToken());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            System.out.println("set refresh and access token and wrote cache");
        });
    }

    private static void authorizationCodeRefreshAsync() {
        AuthorizationCodeRefreshRequest authorizationCodeRefreshRequest = spotifyApi.authorizationCodeRefresh()
                .build();
        final CompletableFuture<AuthorizationCodeCredentials> authorizationCodeCredentialsFuture = authorizationCodeRefreshRequest.executeAsync();

        authorizationCodeCredentialsFuture.whenComplete((authorizationCodeCredentials, throwable) -> {
            spotifyApi.setAccessToken(authorizationCodeCredentials.getAccessToken());
            System.out.println("set access token");
        });
    }

    public static void init(String clientId, String clientSecret) {
        spotifyApi = new SpotifyApi.Builder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRedirectUri(redirectUri)
                .build();
    }

    public static SpotifyApi authorize() throws IOException {
        File refreshFile = new File("refresh_token.txt");
        if(System.currentTimeMillis() < accessTokenExpireDate) {
            // valid
        } else if(spotifyApi.getRefreshToken() != null) {
            System.out.println("access token expired, generating new");
            authorizationCodeRefreshAsync();
        } else if(refreshFile.exists()) {
            System.out.println("cached refresh token found, generating access token");
            String refreshToken = Files.readString(refreshFile.toPath());
            spotifyApi.setRefreshToken(refreshToken);
            authorizationCodeRefreshAsync();
        } else {
            System.out.println("starting auth server, proceed with auth");
            startAuthServer();
            getAuthorizationCodeUriAsync().whenComplete((uri, throwable) -> {
                if (Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    try {
                        desktop.browse(uri);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        return spotifyApi;
    }


    private static void startAuthServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(6123), 0);
        server.createContext("/", exchange -> {
            // use code to get auth
            String code = exchange.getRequestURI().getQuery().split("=")[1];
            code = code.substring(0, code.indexOf("&"));
            processAuthorizationCodeAsync(code);

            String response = "Spotify authenicated successfully, you may close this window.";
            System.out.println(response);
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            server.stop(1);
        });
        server.setExecutor(null);
        server.start();
    }
}
