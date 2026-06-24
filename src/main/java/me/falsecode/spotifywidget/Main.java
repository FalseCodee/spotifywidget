package me.falsecode.spotifywidget;

import se.michaelthelin.spotify.SpotifyApi;

import javax.swing.*;
import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        AuthorizationManager.init(args[0], args[1]);
        try {
            SpotifyApi api = AuthorizationManager.authorize();
            while(api.getAccessToken() == null) {
                System.out.println("No access token, waiting");
                Thread.sleep(500);
            }
            SwingUtilities.invokeLater(() -> new SpotifyNowPlaying(api).setVisible(true));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
