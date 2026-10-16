package pl.botmod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class BotScreen extends Screen {
    private static final int BG = 0xF00E1217;
    private static final int PANEL_BORDER = 0xFF2B323C;
    private static final int FIELD_BG = 0xFF171C23;
    private static final int ACCENT = 0xFF3B82F6;
    private static final int LABEL = 0xFF8B95A5;

    private record Btn(int x, int y, int w, int h, String label, int color, Runnable action, BooleanSupplier enabled, String tip) {
        Btn(int x, int y, int w, int h, String label, int color, Runnable action, BooleanSupplier enabled) {
            this(x, y, w, h, label, color, action, enabled, null);
        }
    }
    private record Slot(int x, int y, int size, Object stack, int kind, int idx) {}
    private record Box(int x, int y, int w, int h) {}
    private record Lbl(int x, int y, String text) {}
    private record Piece(String time, String text, int color) {}

    private TextFieldWidget ipField, nickField, countField, delayField, passField, chatField;
    private final List<TextFieldWidget> fields = new ArrayList<>();
    private final List<Btn> buttons = new ArrayList<>();
    private final List<Box> boxes = new ArrayList<>();
    private final List<Lbl> labels = new ArrayList<>();

    private int px, py, pw, ph, pad = 10;
    private int consoleX, consoleY, consoleW, consoleH;
    private int scroll = 0;

    // zakladki: 0 = Konsola, 1 = GUI bota
    private static int tab = 0;
    private static int botIndex = 0;
    private final List<int[]> tabRects = new ArrayList<>();
    private final List<Slot> frameSlots = new ArrayList<>();
    private final List<Btn> frameBtns = new ArrayList<>();
    private Slot hoverSlot = null;

    public BotScreen() {
        super(Text.literal("Bot Mod"));
    }

    // ------------------------------------------------------------------ uklad

    @Override
    protected void init() {
        fields.clear();
        buttons.clear();
        boxes.clear();
        labels.clear();

        if (BotConfig.address.isEmpty()) {
            ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
            if (info != null) BotConfig.address = info.address;
        }

        pw = Math.min(this.width - 16, 520);
        px = (this.width - pw) / 2;
        py = 8;
        ph = this.height - 16;
        int ix = px + pad;
        int iw = pw - 2 * pad;
        int gap = 4;
        int fh = 18;
        int y = py + 26;

        ipField = null;
        nickField = null;
        countField = null;
        delayField = null;
        passField = null;
        chatField = null;
        if (tab == 1) return;

        // --- adres serwera + lista
        int small = 20;
        int ipW = iw - 4 * (small + gap);
        labels.add(new Lbl(ix, y, "ADRES SERWERA"));
        ipField = field(ix, y + 10, ipW, fh, "np. serwer.pl lub 1.2.3.4:25565", 255, BotConfig.address);
        int bx = ix + ipW + gap;
        buttons.add(new Btn(bx, y + 10, small, fh, "<", 0xFF2A323D, this::prevServer, () -> true));
        buttons.add(new Btn(bx + small + gap, y + 10, small, fh, ">", 0xFF2A323D, this::nextServer, () -> true));
        buttons.add(new Btn(bx + 2 * (small + gap), y + 10, small, fh, "+", 0xFF1F7A45, this::saveServer, () -> true));
        buttons.add(new Btn(bx + 3 * (small + gap), y + 10, small, fh, "x", 0xFF8A2E2E, this::removeServer, () -> true));
        y += 10 + fh + 8;

        // --- nick / ilosc / odstep / haslo
        int avail = iw - 3 * gap;
        int nickW = (int) (avail * 0.34);
        int countW = (int) (avail * 0.14);
        int delayW = (int) (avail * 0.18);
        int passW = avail - nickW - countW - delayW;
        int x = ix;
        labels.add(new Lbl(x, y, "NICK"));
        nickField = field(x, y + 10, nickW, fh, "Nick", 16, BotConfig.nick);
        nickField.setTextPredicate(s -> s.matches("[A-Za-z0-9_]{0,16}"));
        x += nickW + gap;
        labels.add(new Lbl(x, y, "ILOSC"));
        countField = field(x, y + 10, countW, fh, "1", 2, String.valueOf(BotConfig.count));
        countField.setTextPredicate(s -> s.matches("\\d{0,2}"));
        x += countW + gap;
        labels.add(new Lbl(x, y, "ODSTEP (s)"));
        delayField = field(x, y + 10, delayW, fh, "4.5", 4, trimNum(BotConfig.delay));
        delayField.setTextPredicate(s -> s.matches("\\d{0,2}([.,]\\d?)?"));
        x += delayW + gap;
        labels.add(new Lbl(x, y, "HASLO (auto /register)"));
        passField = field(x, y + 10, passW, fh, "haslo", 32, BotConfig.password);
        y += 10 + fh + 8;

        // --- przyciski glowne
        int bh = 20;
        int sendW = (int) ((iw - 2 * gap) * 0.42);
        int stopW = (int) ((iw - 2 * gap) * 0.34);
        int clrW = iw - 2 * gap - sendW - stopW;
        buttons.add(new Btn(ix, y, sendW, bh, "Wyslij boty", 0xFF1F9D55, this::onStart, () -> !BotManager.isActive()));
        buttons.add(new Btn(ix + sendW + gap, y, stopW, bh, "Rozlacz wszystkie", 0xFFD9534F, BotManager::stopAll, BotManager::isActive));
        buttons.add(new Btn(ix + sendW + stopW + 2 * gap, y, clrW, bh, "Wyczysc", 0xFF2A323D, () -> { BotManager.clear(); scroll = 0; }, () -> true));
        y += bh + 8;

        // --- czat (na dole)
        int chatY = py + ph - pad - fh;
        int sendBtnW = 70;
        chatField = field(ix, chatY, iw - sendBtnW - gap, fh, "Wiadomosc lub /komenda (wysle kazdy bot)", 256, "");
        buttons.add(new Btn(ix + iw - sendBtnW, chatY, sendBtnW, fh, "Wyslij", 0xFF2A323D, this::sendChat, () -> BotManager.inGame() > 0));

        // --- konsola
        labels.add(new Lbl(ix, y, "KONSOLA"));
        consoleX = ix;
        consoleY = y + 10;
        consoleW = iw;
        consoleH = Math.max(40, chatY - 6 - consoleY);
    }

    private TextFieldWidget field(int x, int y, int w, int h, String placeholder, int max, String text) {
        boxes.add(new Box(x, y, w, h));
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x + 5, y + (h - 8) / 2, w - 10, 10, Text.literal(placeholder));
        f.setDrawsBackground(false);
        f.setMaxLength(max);
        f.setText(text);
        f.setPlaceholder(Text.literal(placeholder));
        addSelectableChild(f);
        fields.add(f);
        return f;
    }

    private static String trimNum(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ------------------------------------------------------------------ akcje

    private void readFields() {
        if (ipField == null) return;
        BotConfig.address = ipField.getText().trim();
        BotConfig.nick = nickField.getText().trim();
        BotConfig.password = passField.getText();
        try { BotConfig.count = Math.max(1, Math.min(50, Integer.parseInt(countField.getText().trim()))); } catch (NumberFormatException e) { BotConfig.count = 1; }
        try { BotConfig.delay = Math.max(0, Double.parseDouble(delayField.getText().trim().replace(',', '.'))); } catch (NumberFormatException e) { BotConfig.delay = 4.5; }
    }

    private void onStart() {
        readFields();
        BotConfig.save();
        if (!BotConfig.nick.matches("[A-Za-z0-9_]{1,16}")) {
            BotManager.log(BotSession.RED, "Zly nick (1-16 znakow: litery, cyfry, _).");
            return;
        }
        if (BotConfig.address.isEmpty()) {
            BotManager.log(BotSession.RED, "Wpisz adres serwera.");
            return;
        }
        scroll = 0;
        BotManager.start(BotConfig.nick, BotConfig.count, BotConfig.delay, BotConfig.address, BotConfig.password);
    }

    private void sendChat() {
        String t = chatField.getText();
        if (t.isBlank()) return;
        BotManager.sendChat(t);
        chatField.setText("");
    }

    private void saveServer() {
        String a = ipField.getText().trim();
        if (a.isEmpty()) return;
        if (!BotConfig.servers.contains(a)) BotConfig.servers.add(a);
        BotConfig.save();
        BotManager.log(BotSession.GRAY, "Zapisano serwer: " + a);
    }

    private void removeServer() {
        String a = ipField.getText().trim();
        if (BotConfig.servers.remove(a)) {
            BotConfig.save();
            BotManager.log(BotSession.GRAY, "Usunieto serwer: " + a);
        }
    }

    private void cycleServer(int dir) {
        List<String> s = BotConfig.servers;
        if (s.isEmpty()) {
            BotManager.log(BotSession.YELLOW, "Brak zapisanych serwerow - wpisz adres i kliknij +.");
            return;
        }
        int i = s.indexOf(ipField.getText().trim());
        i = i < 0 ? (dir > 0 ? 0 : s.size() - 1) : Math.floorMod(i + dir, s.size());
        ipField.setText(s.get(i));
    }

    private void prevServer() { cycleServer(-1); }
    private void nextServer() { cycleServer(1); }

    // ------------------------------------------------------------------ rysowanie

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x99000000);
        ctx.fill(px, py, px + pw, py + ph, BG);
        ctx.drawBorder(px, py, pw, ph, PANEL_BORDER);

        // naglowek: tytul + zakladki + status
        ctx.drawTextWithShadow(this.textRenderer, "BotMod", px + pad, py + 9, 0xFFFFFF);
        int titleW = this.textRenderer.getWidth("BotMod");
        tabRects.clear();
        String[] tabNames = {"Konsola", "GUI bota"};
        int tabX = px + pad + titleW + 14;
        for (int i = 0; i < tabNames.length; i++) {
            int w = this.textRenderer.getWidth(tabNames[i]) + 14;
            boolean active = tab == i;
            boolean hov = inRect(mouseX, mouseY, tabX, py + 3, w, 18);
            if (hov && !active) ctx.fill(tabX, py + 3, tabX + w, py + 21, 0x22FFFFFF);
            ctx.drawTextWithShadow(this.textRenderer, tabNames[i], tabX + 7, py + 9, active ? 0xFFFFFF : 0xFF9CA3AF);
            if (active) ctx.fill(tabX + 3, py + 20, tabX + w - 3, py + 22, ACCENT);
            tabRects.add(new int[]{tabX, py + 3, w, 18});
            tabX += w + 4;
        }
        int in = BotManager.inGame();
        int run = BotManager.running();
        int total = BotManager.planned();
        String status = run == 0 ? "Rozlaczony" : "Na serwerze " + in + "/" + Math.max(total, run);
        int dot = run == 0 ? 0xFF6B7280 : (in > 0 ? 0xFF22C55E : 0xFFEAB308);
        int sw = this.textRenderer.getWidth(status);
        int sx = px + pw - pad - sw;
        ctx.fill(sx - 12, py + 11, sx - 6, py + 17, dot);
        ctx.drawTextWithShadow(this.textRenderer, status, sx, py + 9, 0xFFE5E7EB);
        ctx.fill(px + 1, py + 23, px + pw - 1, py + 24, PANEL_BORDER);

        if (tab == 1) {
            renderBotTab(ctx, mouseX, mouseY);
            return;
        }

        // etykiety
        for (Lbl l : labels) ctx.drawTextWithShadow(this.textRenderer, l.text(), l.x(), l.y(), LABEL | 0xFF000000);

        // pola
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            ctx.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), FIELD_BG);
            ctx.drawBorder(b.x(), b.y(), b.w(), b.h(), fields.get(i).isFocused() ? ACCENT : PANEL_BORDER);
        }
        for (TextFieldWidget f : fields) f.render(ctx, mouseX, mouseY, delta);

        // przyciski
        for (Btn b : buttons) {
            boolean on = b.enabled().getAsBoolean();
            boolean hover = on && inRect(mouseX, mouseY, b.x(), b.y(), b.w(), b.h());
            int col = on ? (hover ? lighten(b.color()) : b.color()) : 0xFF1C2128;
            ctx.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), col);
            ctx.drawBorder(b.x(), b.y(), b.w(), b.h(), on ? 0x33FFFFFF : 0x22FFFFFF);
            int tx = b.x() + b.w() / 2;
            int ty = b.y() + (b.h() - 8) / 2;
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(b.label()), tx, ty, on ? 0xFFFFFF : 0x6B7280);
        }

        renderConsole(ctx);
    }

    private void renderConsole(DrawContext ctx) {
        ctx.fill(consoleX, consoleY, consoleX + consoleW, consoleY + consoleH, 0xFF0A0D11);
        ctx.drawBorder(consoleX, consoleY, consoleW, consoleH, PANEL_BORDER);

        List<BotManager.Line> lines = BotManager.lines();
        if (lines.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, "Tu pojawia sie konsola botow.", consoleX + 6, consoleY + 6, 0xFF4B5563);
            return;
        }

        int timeW = this.textRenderer.getWidth("00:00:00") + 6;
        int maxW = consoleW - 14 - timeW;
        List<Piece> wrapped = new ArrayList<>();
        for (BotManager.Line l : lines) {
            String rest = l.text;
            boolean first = true;
            do {
                String part = this.textRenderer.trimToWidth(rest, maxW);
                if (part.isEmpty()) part = rest.substring(0, 1);
                wrapped.add(new Piece(first ? l.time : "", part, l.color));
                rest = rest.substring(part.length());
                first = false;
            } while (!rest.isEmpty());
        }

        int lineH = this.textRenderer.fontHeight + 2;
        int visible = Math.max(1, (consoleH - 8) / lineH);
        int maxScroll = Math.max(0, wrapped.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int from = Math.max(0, wrapped.size() - visible - scroll);
        int y = consoleY + 5;
        for (int i = from; i < Math.min(wrapped.size(), from + visible); i++) {
            Piece p = wrapped.get(i);
            if (!p.time().isEmpty()) {
                ctx.drawTextWithShadow(this.textRenderer, p.time(), consoleX + 6, y, 0xFF5B6573);
            }
            ctx.drawTextWithShadow(this.textRenderer, p.text(), consoleX + 6 + timeW, y, p.color() | 0xFF000000);
            y += lineH;
        }

        if (maxScroll > 0) {
            int trackH = consoleH - 4;
            int thumbH = Math.max(10, trackH * visible / wrapped.size());
            int thumbY = consoleY + 2 + (trackH - thumbH) * (maxScroll - scroll) / maxScroll;
            ctx.fill(consoleX + consoleW - 5, thumbY, consoleX + consoleW - 2, thumbY + thumbH, 0xFF3B4452);
        }
    }

    // ------------------------------------------------------------------ zakladka "GUI bota"

    private BotSession selectedBot() {
        List<BotSession> l = new ArrayList<>(BotManager.bots());
        l.removeIf(b -> !b.isInGame());
        if (l.isEmpty()) return null;
        botIndex = Math.floorMod(botIndex, l.size());
        return l.get(botIndex);
    }

    private static InventoryModel modelOf(BotSession b) {
        return b != null && b.hook() instanceof InventoryModel m ? m : null;
    }

    private void label(DrawContext ctx, String text, int x, int y) {
        ctx.drawTextWithShadow(this.textRenderer, text, x, y, LABEL | 0xFF000000);
    }

    private String itemName(Object stack) {
        return stack instanceof ItemStack st ? st.getName().getString() : "";
    }

    private void drawSlot(DrawContext ctx, int x, int y, int cell, Object stack, int kind, int idx,
                          int mx, int my, boolean selected) {
        int sz = cell - 1;
        ctx.fill(x, y, x + sz, y + sz, 0xFF161B22);
        ctx.drawBorder(x, y, sz, sz, selected ? 0xFF22C55E : PANEL_BORDER);
        if (stack instanceof ItemStack st) {
            int ix = x + (sz - 16) / 2;
            int iy = y + (sz - 16) / 2;
            ctx.drawItem(st, ix, iy);
            ctx.drawStackOverlay(this.textRenderer, st, ix, iy);
        }
        if (inRect(mx, my, x, y, sz, sz)) {
            ctx.fill(x + 1, y + 1, x + sz - 1, y + sz - 1, 0x40FFFFFF);
            hoverSlot = new Slot(x, y, sz, stack, kind, idx);
        }
        frameSlots.add(new Slot(x, y, sz, stack, kind, idx));
    }

    private void frameButton(DrawContext ctx, int x, int y, int w, int h, String label, int color,
                             Runnable action, boolean enabled, String tip, int mx, int my) {
        boolean hover = enabled && inRect(mx, my, x, y, w, h);
        int col = enabled ? (hover ? lighten(color) : color) : 0xFF1C2128;
        ctx.fill(x, y, x + w, y + h, col);
        ctx.drawBorder(x, y, w, h, enabled ? 0x33FFFFFF : 0x22FFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), x + w / 2, y + (h - 8) / 2,
                enabled ? 0xFFFFFF : 0x6B7280);
        frameBtns.add(new Btn(x, y, w, h, label, color, action, () -> enabled, tip));
    }

    private void renderBotTab(DrawContext ctx, int mx, int my) {
        frameSlots.clear();
        frameBtns.clear();
        hoverSlot = null;

        int x0 = px + pad;
        int iw = pw - 2 * pad;
        int top = py + 30;
        int bottom = py + ph - pad - 12;

        ctx.drawTextWithShadow(this.textRenderer, "Shift - szybkie przenoszenie · 1-9 - zamiana · Q - wyrzuc",
                x0, py + ph - pad - 8, 0xFF5B6573);

        BotSession bot = selectedBot();
        InventoryModel m = modelOf(bot);
        if (m == null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Brak bota na serwerze - wyslij bota w zakladce Konsola."),
                    px + pw / 2, py + ph / 2 - 4, 0xFF9CA3AF);
            return;
        }

        int cell = Math.max(14, Math.min(18, (iw - 16 - 100) / 18));
        int gridW = 9 * cell;
        int midX = x0 + gridW + 8;
        int midW = iw - 2 * gridW - 16;
        int rightX = x0 + iw - gridW;

        // ---------------- lewa kolumna: okno, zbroja, crafting
        int y = top;
        boolean open = m.hasOpen();
        if (open) {
            String title = this.textRenderer.trimToWidth("OTWARTE GUI: " + m.openTitle(), gridW);
            label(ctx, title, x0, y);
            y += 11;
            int n = m.openSize();
            int cols = n > 9 ? 9 : Math.max(1, n);
            int rows = (n + cols - 1) / cols;
            for (int i = 0; i < n; i++) {
                drawSlot(ctx, x0 + (i % cols) * cell, y + (i / cols) * cell, cell, m.openSlot(i),
                        InventoryModel.K_CONT, i, mx, my, false);
            }
            y += rows * cell + 4;
        } else {
            label(ctx, "BRAK OTWARTEGO GUI", x0, y);
            y += 11;
            ctx.drawTextWithShadow(this.textRenderer, "Bot nie ma otwartego okna.", x0, y, 0xFF9CA3AF);
            y += 14;
        }
        if (y + 11 + cell <= bottom) {
            label(ctx, "Zbroja i druga reka", x0, y);
            y += 11;
            for (int i = 0; i < 4; i++) {
                drawSlot(ctx, x0 + i * cell, y, cell, m.armor(i), InventoryModel.K_ARMOR, i, mx, my, false);
            }
            drawSlot(ctx, x0 + 4 * cell + 8, y, cell, m.offhand(), InventoryModel.K_OFF, 0, mx, my, false);
            y += cell + 4;
        }
        if (!open && y + 11 + 2 * cell <= bottom) {
            label(ctx, "Craftowanie 2x2", x0, y);
            y += 11;
            for (int i = 0; i < 4; i++) {
                drawSlot(ctx, x0 + (i % 2) * cell, y + (i / 2) * cell, cell, m.craft(i), InventoryModel.K_CRAFT, i, mx, my, false);
            }
            ctx.drawTextWithShadow(this.textRenderer, ">", x0 + 2 * cell + 6, y + cell - 4, 0xFF9CA3AF);
            drawSlot(ctx, x0 + 2 * cell + 20, y + cell / 2, cell, m.craftResult(), InventoryModel.K_RES, 0, mx, my, false);
        }

        // ---------------- srodek: wybor bota, w rece, przyciski
        int cx = midX + midW / 2;
        int my0 = top;
        label(ctx, "BOT", midX, my0);
        String nm = this.textRenderer.trimToWidth(bot.name(), midW - 44);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(nm), cx, my0 + 11, 0xFFFFFF);
        frameButton(ctx, midX, my0 + 7, 16, 16, "<", 0xFF2A323D, () -> botIndex--, true, null, mx, my);
        frameButton(ctx, midX + midW - 16, my0 + 7, 16, 16, ">", 0xFF2A323D, () -> botIndex++, true, null, mx, my);

        int hy = my0 + 30;
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("W RECE"), cx, hy, LABEL | 0xFF000000);
        Object inHand = m.hotbar(m.selected());
        int hs = cell + 8;
        int hx = cx - hs / 2;
        ctx.fill(hx, hy + 11, hx + hs, hy + 11 + hs, 0xFF161B22);
        ctx.drawBorder(hx, hy + 11, hs, hs, PANEL_BORDER);
        if (inHand instanceof ItemStack st) {
            ctx.drawItem(st, hx + (hs - 16) / 2, hy + 11 + (hs - 16) / 2);
            ctx.drawStackOverlay(this.textRenderer, st, hx + (hs - 16) / 2, hy + 11 + (hs - 16) / 2);
        }
        String handName = inHand == null ? "pusta reka" : this.textRenderer.trimToWidth(itemName(inHand), midW);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(handName), cx, hy + 14 + hs, 0xFFD1D5DB);

        int by = hy + 26 + hs;
        int bh = 16;
        frameButton(ctx, midX, by, midW, bh, "LPM", 0xFF1F7A45, m::swing, true,
                "Bot macha reka (lewy przycisk myszy)", mx, my);
        by += bh + 3;
        frameButton(ctx, midX, by, midW, bh, "PPM", 0xFF1F7A45, m::useItem, true,
                "Bot klika prawym przyciskiem trzymanym przedmiotem (np. otwiera menu z itemu)", mx, my);
        by += bh + 3;
        frameButton(ctx, midX, by, midW, bh, m.isHolding() ? "Puszcz PPM" : "Trzymaj PPM",
                m.isHolding() ? 0xFFB7791F : 0xFF8A6A1A, m::toggleHold, true,
                "Bot klika PPM raz na 0,25 s, dopoki nie klikniesz ponownie", mx, my);
        by += bh + 3;
        frameButton(ctx, midX, by, midW, bh, "Wyrzuc", 0xFF8A2E2E, () -> m.drop(hasShiftDown()), true,
                "Wyrzuca przedmiot z reki (z Shiftem - caly stos)", mx, my);
        by += bh + 5;
        if (m.cursor() != null) {
            String cur = this.textRenderer.trimToWidth("Kursor: " + itemName(m.cursor()), midW);
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(cur), cx, by, 0xFFFACC15);
            by += 11;
        }
        if (m.isDesync()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Ekwipunek niepelny"), cx, by, 0xFFEF4444);
        }

        // ---------------- prawa kolumna: ekwipunek bota
        int ry = top;
        label(ctx, "EKWIPUNEK BOTA", rightX, ry);
        ry += 11;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                drawSlot(ctx, rightX + c * cell, ry + r * cell, cell, m.main(r * 9 + c),
                        InventoryModel.K_MAIN, r * 9 + c, mx, my, false);
            }
        }
        ry += 3 * cell + 4;
        for (int c = 0; c < 9; c++) {
            drawSlot(ctx, rightX + c * cell, ry, cell, m.hotbar(c), InventoryModel.K_HOT, c, mx, my, m.selected() == c);
        }
        ry += cell + 3;
        for (int c = 0; c < 9; c++) {
            final int idx = c;
            boolean sel = m.selected() == c;
            frameButton(ctx, rightX + c * cell, ry, cell - 2, 12, String.valueOf(c + 1),
                    sel ? 0xFF22A559 : 0xFF2A323D, () -> m.select(idx), true,
                    "Wybierz slot " + (c + 1) + " w pasku", mx, my);
        }

        // ---------------- podpowiedzi
        if (hoverSlot != null && hoverSlot.stack() instanceof ItemStack st) {
            ctx.drawItemTooltip(this.textRenderer, st, mx, my);
        } else {
            for (Btn b : frameBtns) {
                if (b.tip() != null && inRect(mx, my, b.x(), b.y(), b.w(), b.h())) {
                    ctx.drawTooltip(this.textRenderer, Text.literal(b.tip()), mx, my);
                    break;
                }
            }
        }
    }

    private void slotClick(Slot s, int button) {
        InventoryModel m = modelOf(selectedBot());
        if (m == null) return;
        int ws = m.windowSlot(s.kind(), s.idx());
        if (ws < 0) return;
        if (hasShiftDown()) m.quickMove(ws);
        else m.pickup(ws, button == 1);
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int lighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 28);
        int b = Math.min(255, (argb & 0xFF) + 28);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ wejscie

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int i = 0; i < tabRects.size(); i++) {
                int[] r = tabRects.get(i);
                if (inRect(mouseX, mouseY, r[0], r[1], r[2], r[3])) {
                    if (tab != i) {
                        readFields();
                        tab = i;
                        this.clearAndInit();
                    }
                    return true;
                }
            }
        }
        if (tab == 1) {
            if (button == 0) {
                for (Btn b : new ArrayList<>(frameBtns)) {
                    if (b.enabled().getAsBoolean() && inRect(mouseX, mouseY, b.x(), b.y(), b.w(), b.h())) {
                        b.action().run();
                        return true;
                    }
                }
            }
            if (button == 0 || button == 1) {
                for (Slot sl : new ArrayList<>(frameSlots)) {
                    if (inRect(mouseX, mouseY, sl.x(), sl.y(), sl.size(), sl.size())) {
                        slotClick(sl, button);
                        return true;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            for (Btn b : buttons) {
                if (b.enabled().getAsBoolean() && inRect(mouseX, mouseY, b.x(), b.y(), b.w(), b.h())) {
                    b.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (inRect(mouseX, mouseY, consoleX, consoleY, consoleW, consoleH)) {
            scroll += (int) Math.signum(verticalAmount) * 3;
            if (scroll < 0) scroll = 0;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (tab == 1 && hoverSlot != null) {
            InventoryModel m = modelOf(selectedBot());
            if (m != null) {
                int ws = m.windowSlot(hoverSlot.kind(), hoverSlot.idx());
                if (ws >= 0 && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
                    m.swapHotbar(ws, keyCode - GLFW.GLFW_KEY_1);
                    return true;
                }
                if (ws >= 0 && keyCode == GLFW.GLFW_KEY_Q) {
                    m.throwItem(ws, hasControlDown());
                    return true;
                }
            }
        }
        if (chatField != null && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && chatField.isFocused()) {
            sendChat();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        readFields();
        BotConfig.save();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
