package me.falsecode.spotifywidget;

import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.*;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Compact always-on-top Spotify widget.
 * Background is a heavily blurred + darkened scale-up of the album art (Apple-style ambient).
 * When unfocused, the background fades to near-transparent while content stays opaque.
 *
 * Usage:
 *   SpotifyNowPlaying widget = new SpotifyNowPlaying(spotifyApi);
 *   widget.setVisible(true);
 */
public class SpotifyNowPlaying extends JFrame {

    // ── Content colours (always fully opaque) ────────────────────────────────
    private static final Color ACCENT         = new Color(0xFFFFFF);
    private static final Color TEXT_PRIMARY   = new Color(0xF5F5F5);
    private static final Color TEXT_SECONDARY = new Color(0xC0C0C0);

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int W        = 390;
    private static final int PAD      = 12;
    private static final int ART      = 58;
    private static final int ART_R    = 6;
    private static final int WIN_R    = 14;
    private static final int BAR_H    = 3;
    private static final int POLL_SEC = 2;

    // ── Blur / dim settings ───────────────────────────────────────────────────
    /** How many box-blur passes to stack (more = smoother but slower once per track). */
    private static final int   BLUR_PASSES   = 6;
    /** Radius of each box-blur pass in pixels. */
    private static final int   BLUR_RADIUS   = 8;
    /** Black overlay alpha on the blurred art (0–255). Higher = darker / more readable. */
    private static final int   DIM_FOCUSED   = 120;  // ~47% dark overlay when focused

    // ── Fonts ─────────────────────────────────────────────────────────────────
    private static final Font FONT_TITLE  = new Font("Helvetica Neue", Font.BOLD,  15);
    private static final Font FONT_ARTIST = new Font("Helvetica Neue", Font.PLAIN, 13);
    private static final Font FONT_TIME   = new Font("Helvetica Neue", Font.PLAIN, 11);

    // ── Spotify ───────────────────────────────────────────────────────────────
    private final SpotifyApi spotifyApi;

    // ── State ─────────────────────────────────────────────────────────────────
    private String        currentTrackId = null;
    private BufferedImage currentArt     = null;  // raw downloaded art
    private BufferedImage blurredBg      = null;  // pre-baked blurred background

    // ── Components ────────────────────────────────────────────────────────────
    private final WidgetPanel widget;
    private final ScheduledExecutorService scheduler;

    private Point dragOrigin;

    // ─────────────────────────────────────────────────────────────────────────
    public SpotifyNowPlaying(SpotifyApi spotifyApi) {
        this.spotifyApi = spotifyApi;

        setUndecorated(true);
        setAlwaysOnTop(true);
        setBackground(new Color(0, 0, 0, 0));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        widget = new WidgetPanel();
        add(widget);
        pack();

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation(screen.width - W - 20, screen.height - getHeight() - 60);

        // ── Drag ──────────────────────────────────────────────────────────────
        widget.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { dragOrigin = e.getPoint(); }
            @Override public void mouseReleased(MouseEvent e) { dragOrigin = null; }
        });
        widget.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOrigin == null) return;
                Point loc = getLocation();
                setLocation(loc.x + e.getX() - dragOrigin.x,
                        loc.y + e.getY() - dragOrigin.y);
            }
        });

        // Right-click to close
        widget.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) dispose();
            }
        });

        // ── Focus → swap dim overlay ──────────────────────────────────────────
        addWindowFocusListener(new WindowFocusListener() {
            @Override public void windowGainedFocus(WindowEvent e) { widget.setFocused(true); }
            @Override public void windowLostFocus(WindowEvent e)   { widget.setFocused(false); }
        });
        widget.setFocused(false);

        // ── Polling ───────────────────────────────────────────────────────────
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spotify-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::pollSpotify, 0, POLL_SEC, TimeUnit.SECONDS);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                scheduler.shutdownNow();
                widget.stopMarquee();
            }
        });
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private void pollSpotify() {
        try {
            CurrentlyPlayingContext ctx = spotifyApi
                    .getInformationAboutUsersCurrentPlayback()
                    .build()
                    .execute();

            if (ctx == null || ctx.getItem() == null) {
                SwingUtilities.invokeLater(() -> widget.showIdle());
                return;
            }

            Track   track    = (Track) ctx.getItem();
            boolean playing  = ctx.getIs_playing();
            int     progress = ctx.getProgress_ms();
            int     duration = track.getDurationMs();

            String trackId = track.getId();
            if (!trackId.equals(currentTrackId)) {
                currentTrackId = trackId;
                loadArtwork(track);         // also bakes blurredBg
            }

            String title   = track.getName();
            String artists = Arrays.stream(track.getArtists())
                    .map(ArtistSimplified::getName)
                    .collect(Collectors.joining(", "));

            SwingUtilities.invokeLater(() ->
                    widget.update(currentArt, blurredBg, title, artists, progress, duration, playing));

        } catch (Exception ex) {
            try {
                AuthorizationManager.authorize();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            SwingUtilities.invokeLater(() -> widget.showError());
        }
    }

    private void loadArtwork(Track track) {
        Image[] images = track.getAlbum().getImages();
        if (images == null || images.length == 0) {
            currentArt = null; blurredBg = null; return;
        }
        // Prefer smallest image that is still ≥ ART pixels wide
        Image best = images[images.length - 1];
        for (int i = images.length - 1; i >= 0; i--) {
            if (images[i].getWidth() >= ART) { best = images[i]; break; }
        }
        try {
            currentArt = ImageIO.read(new URL(best.getUrl()));
            blurredBg  = buildBlurredBackground(currentArt, W, widget.getHeight());
        } catch (IOException e) {
            currentArt = null; blurredBg = null;
        }
    }

    // ── Background baking ─────────────────────────────────────────────────────

    /**
     * Scale art to fill targetW×targetH (crop-to-fill), then apply repeated box blurs.
     * Returns a BufferedImage the exact size of the widget, ready to draw at (0,0).
     */
    private static BufferedImage buildBlurredBackground(BufferedImage src, int targetW, int targetH) {
        if (src == null || targetW <= 0 || targetH <= 0) return null;

        // 1. Scale to fill — maintain aspect ratio, crop centre
        double scaleX = (double) targetW / src.getWidth();
        double scaleY = (double) targetH / src.getHeight();
        double scale  = Math.max(scaleX, scaleY) * 1.3;  // 30% overscan so blur edge softens

        int scaledW = (int)(src.getWidth()  * scale);
        int scaledH = (int)(src.getHeight() * scale);
        int offsetX = (scaledW - targetW) / 2;
        int offsetY = (scaledH - targetH) / 2;

        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, -offsetX, -offsetY, scaledW, scaledH, null);
        g.dispose();

        // 2. Stack box-blur passes
        BufferedImage blurred = scaled;
        for (int i = 0; i < BLUR_PASSES; i++) {
            blurred = boxBlur(blurred, BLUR_RADIUS);
        }

        return blurred;
    }

    /**
     * Single horizontal + vertical box blur pass.
     * Pure Java — no ConvolveOp kernel size limit issues.
     */
    private static BufferedImage boxBlur(BufferedImage src, int radius) {
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getRGB(0, 0, w, h, pixels, 0, w);

        int[] tmp = new int[w * h];
        int   len = radius * 2 + 1;

        // Horizontal pass
        for (int y = 0; y < h; y++) {
            int rSum = 0, gSum = 0, bSum = 0, aSum = 0;
            for (int x = -radius; x <= radius; x++) {
                int px = pixels[y * w + Math.max(0, Math.min(w - 1, x))];
                aSum += (px >> 24) & 0xFF;
                rSum += (px >> 16) & 0xFF;
                gSum += (px >>  8) & 0xFF;
                bSum +=  px        & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                tmp[y * w + x] = ((aSum / len) << 24) | ((rSum / len) << 16)
                        | ((gSum / len) <<  8) |  (bSum / len);
                int add = pixels[y * w + Math.min(w - 1, x + radius + 1)];
                int rem = pixels[y * w + Math.max(0,     x - radius)];
                aSum += ((add >> 24) & 0xFF) - ((rem >> 24) & 0xFF);
                rSum += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                gSum += ((add >>  8) & 0xFF) - ((rem >>  8) & 0xFF);
                bSum += ( add        & 0xFF) - ( rem        & 0xFF);
            }
        }

        // Vertical pass
        int[] out = new int[w * h];
        for (int x = 0; x < w; x++) {
            int rSum = 0, gSum = 0, bSum = 0, aSum = 0;
            for (int y = -radius; y <= radius; y++) {
                int px = tmp[Math.max(0, Math.min(h - 1, y)) * w + x];
                aSum += (px >> 24) & 0xFF;
                rSum += (px >> 16) & 0xFF;
                gSum += (px >>  8) & 0xFF;
                bSum +=  px        & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((aSum / len) << 24) | ((rSum / len) << 16)
                        | ((gSum / len) <<  8) |  (bSum / len);
                int add = tmp[Math.min(h - 1, y + radius + 1) * w + x];
                int rem = tmp[Math.max(0,     y - radius)     * w + x];
                aSum += ((add >> 24) & 0xFF) - ((rem >> 24) & 0xFF);
                rSum += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                gSum += ((add >>  8) & 0xFF) - ((rem >>  8) & 0xFF);
                bSum += ( add        & 0xFF) - ( rem        & 0xFF);
            }
        }

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, out, 0, w);
        return result;
    }

    private static String fmt(int ms) {
        int s = ms / 1000, m = s / 60; s %= 60;
        return String.format("%d:%02d", m, s);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  WidgetPanel
    // ═══════════════════════════════════════════════════════════════════════════
    private class WidgetPanel extends JPanel {

        private BufferedImage art;
        private BufferedImage bgBlurred;   // pre-baked blurred background
        private String  title    = "Connecting…";
        private String  artist   = "";
        private String  elapsed  = "0:00";
        private String  duration = "0:00";
        private int     progress = 0;
        private boolean playing  = false;
        private boolean focused  = false;

        private int     marqueeOffset = 0;
        private Timer   marqueeTimer;
        private boolean needsMarquee  = false;

        WidgetPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(W, PAD + ART + PAD + BAR_H + PAD));
        }

        void setFocused(boolean f) {
            if (this.focused == f) return;
            this.focused = f;
            repaint();
        }

        void update(BufferedImage newArt, BufferedImage newBg,
                    String newTitle, String newArtist,
                    int progressMs, int durationMs, boolean isPlaying) {
            this.art       = newArt;
            this.bgBlurred = newBg;
            this.artist    = newArtist;
            this.elapsed   = fmt(progressMs);
            this.duration  = fmt(durationMs);
            this.progress  = durationMs > 0 ? (int)(100L * progressMs / durationMs) : 0;
            this.playing   = isPlaying;
            if (!newTitle.equals(this.title)) {
                this.title = newTitle;
                resetMarquee();
            }
            repaint();
        }

        void showIdle() {
            this.art = null; this.bgBlurred = null;
            this.title = "Nothing playing"; this.artist = "";
            this.elapsed = "0:00"; this.duration = "0:00";
            this.progress = 0; this.playing = false;
            resetMarquee(); repaint();
        }

        void showError() {
            this.title = "API error"; this.artist = "";
            resetMarquee(); repaint();
        }

        void stopMarquee() {
            if (marqueeTimer != null) { marqueeTimer.stop(); marqueeTimer = null; }
        }

        private void resetMarquee() {
            stopMarquee(); marqueeOffset = 0; needsMarquee = false;
            SwingUtilities.invokeLater(this::checkMarquee);
        }

        private void checkMarquee() {
            FontMetrics fm = getFontMetrics(FONT_TITLE);
            if (fm == null) return;
            int allowedW = W - PAD * 3 - ART;
            needsMarquee = fm.stringWidth(title) > allowedW;
            if (needsMarquee) startMarquee(fm.stringWidth(title));
        }

        private void startMarquee(int textW) {
            marqueeTimer = new Timer(30, e -> {
                marqueeOffset = (marqueeOffset + 1) % (textW + 32);
                repaint();
            });
            marqueeTimer.setInitialDelay(2000);
            marqueeTimer.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,     RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            int w = getWidth(), h = getHeight();
            Shape pill = new RoundRectangle2D.Float(0, 0, w, h, WIN_R * 2, WIN_R * 2);

            // ── 1. Clip everything to the pill shape ──────────────────────────
            g2.setClip(pill);

            // ── 2. Blurred art background (or flat fallback) ──────────────────
            // When unfocused, draw the art at very low opacity so the pill
            // background becomes mostly transparent — content stays opaque on top.
            if (bgBlurred != null) {
                float artAlpha = focused ? 1.0f : 0.08f;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, artAlpha));
                g2.drawImage(bgBlurred, 0, 0, w, h, null);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
            } else {
                int bgAlpha = focused ? 200 : 15;
                g2.setColor(new Color(30, 30, 30, bgAlpha));
                g2.fillRect(0, 0, w, h);
            }

            // ── 3. Dark overlay — present when focused, whisper when not ──────
            int dimAlpha = focused ? DIM_FOCUSED : 10;
            g2.setColor(new Color(0, 0, 0, dimAlpha));
            g2.fillRect(0, 0, w, h);

            // ── 4. Remove pill clip before drawing content ────────────────────
            g2.setClip(null);

            // ── 5. Pill border ────────────────────────────────────────────────
            g2.setColor(new Color(255, 255, 255, focused ? 25 : 50));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(pill);

            // ── 6. Album art thumbnail ────────────────────────────────────────
            int artX = PAD, artY = PAD;
            g2.setClip(new RoundRectangle2D.Float(artX, artY, ART, ART, ART_R, ART_R));
            if (art != null) {
                g2.drawImage(art, artX, artY, ART, ART, null);
            } else {
                g2.setColor(new Color(50, 50, 50));
                g2.fillRoundRect(artX, artY, ART, ART, ART_R, ART_R);
                g2.setColor(new Color(110, 110, 110));
                g2.setFont(new Font("Dialog", Font.PLAIN, 22));
                FontMetrics fm = g2.getFontMetrics();
                String note = "♫";
                g2.drawString(note, artX + (ART - fm.stringWidth(note)) / 2,
                        artY + (ART + fm.getAscent()) / 2 - 3);
            }
            g2.setClip(null);

            // ── 7. Text ───────────────────────────────────────────────────────
            int textX  = PAD + ART + PAD;
            int textW  = w - textX - PAD;
            int titleY = PAD + 16;

            // Playing indicator dot
            g2.setColor(playing ? ACCENT : new Color(160, 160, 160));
            g2.fillOval(textX, titleY - 8, 6, 6);

            // Title
            g2.setClip(textX + 10, PAD, textW - 10, ART / 2 + 2);
            g2.setFont(FONT_TITLE);
            g2.setColor(TEXT_PRIMARY);
            if (needsMarquee) {
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(title);
                g2.drawString(title, textX + 10 - marqueeOffset, titleY);
                g2.drawString(title, textX + 10 - marqueeOffset + tw + 32, titleY);
            } else {
                g2.drawString(title, textX + 10, titleY);
            }
            g2.setClip(null);

            // Artist
            g2.setFont(FONT_ARTIST);
            g2.setColor(TEXT_SECONDARY);
            g2.drawString(clipText(g2, artist, textW), textX, PAD + ART - 4);

            // Time
            FontMetrics fmTime = g2.getFontMetrics(FONT_TIME);
            g2.setFont(FONT_TIME);
            g2.setColor(TEXT_SECONDARY);
            String timeStr = elapsed + " / " + duration;
            g2.drawString(timeStr, w - PAD - fmTime.stringWidth(timeStr), PAD + ART - 4);

            // ── 8. Progress bar ───────────────────────────────────────────────
            int barY = PAD + ART + PAD;
            int barW = w - PAD * 2;
            g2.setColor(new Color(255, 255, 255, 50));
            g2.fillRoundRect(PAD, barY, barW, BAR_H, BAR_H, BAR_H);
            if (progress > 0) {
                g2.setColor(ACCENT);
                g2.fillRoundRect(PAD, barY, (int)(barW * progress / 100.0), BAR_H, BAR_H, BAR_H);
            }

            g2.dispose();
        }

        private String clipText(Graphics2D g2, String text, int maxW) {
            FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(text) <= maxW) return text;
            String ell = "…";
            int ellW = fm.stringWidth(ell);
            StringBuilder sb = new StringBuilder(text);
            while (sb.length() > 0 && fm.stringWidth(sb.toString()) + ellW > maxW)
                sb.deleteCharAt(sb.length() - 1);
            return sb + ell;
        }
    }
}