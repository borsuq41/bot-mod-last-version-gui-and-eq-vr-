package pl.botmod;

import java.io.*;
import java.util.Arrays;

/**
 * Stan ekwipunku bota (okno 0 + aktualnie otwarte okno) oraz akcje: klikanie slotow, zmiana slotu w rece,
 * machniecie, uzycie przedmiotu, wyrzucanie. Czysta Java - odczyt przedmiotow robi ItemReader.
 * Numery pakietow podane sa wzgledem pakietu Login (tak samo jak w BotSession).
 */
public final class InventoryModel implements BotSession.PlayHook {

    public interface ItemReader {
        /** Czyta jeden przedmiot (format ItemStack z protokolu). Zwraca null dla pustego slotu. */
        Object read(Cursor c) throws IOException;
    }

    public static final class Cursor {
        public final byte[] data;
        public int pos;

        public Cursor(byte[] data) { this.data = data; }

        public int varInt() throws IOException {
            int v = 0, shift = 0, b;
            do {
                if (pos >= data.length) throw new EOFException();
                b = data[pos++] & 0xFF;
                v |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new IOException("VarInt za duzy");
            } while ((b & 0x80) != 0);
            return v;
        }

        public int s16() throws IOException {
            if (pos + 2 > data.length) throw new EOFException();
            int v = (short) (((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF));
            pos += 2;
            return v;
        }
    }

    // rodzaje slotow w GUI
    public static final int K_CONT = 0, K_MAIN = 1, K_HOT = 2, K_ARMOR = 3, K_OFF = 4, K_CRAFT = 5, K_RES = 6;

    // clientbound (wzgledem Login)
    static final int OFF_CLOSE = -26, OFF_CONTENT = -25, OFF_SLOT = -23, OFF_OPEN = 9,
            OFF_CURSOR = 46, OFF_HELD = 55, OFF_INV_SLOT = 58;
    // serverbound (1.21.4: pakiet "pick item" jest rozdzielony na dwa, wiec numery od 0x22 wzwyz sa przesuniete o 1)
    static final int S_CLICK = 0x10, S_ACTION = 0x27, S_HELD = 0x32, S_SWING = 0x39, S_USE = 0x3C;

    private final BotSession bot;
    private final ItemReader reader;
    private final Object lock = new Object();

    private Object[] inv = new Object[46];
    private int invState = 0;
    private int openId = 0;
    private Object[] open = null;
    private int openState = 0;
    private String openTitle = "";
    private Object cursor = null;
    private int selected = 0;
    private boolean desync = false;
    private int sequence = 0;
    private boolean hold = false;

    public InventoryModel(BotSession bot, ItemReader reader) {
        this.bot = bot;
        this.reader = reader;
    }

    // ------------------------------------------------------------------ pakiety

    @Override
    public void onLogin() {
        synchronized (lock) {
            inv = new Object[46];
            invState = 0;
            open = null;
            openId = 0;
            cursor = null;
            selected = 0;
            desync = false;
            hold = false;
        }
    }

    @Override
    public void onError() {
        synchronized (lock) { desync = true; }
    }

    @Override
    public void onPlay(int rel, byte[] body) throws Exception {
        switch (rel) {
            case OFF_CONTENT -> content(new Cursor(body));
            case OFF_SLOT -> slot(new Cursor(body));
            case OFF_CURSOR -> {
                Object it = reader.read(new Cursor(body));
                synchronized (lock) { cursor = it; }
            }
            case OFF_HELD -> {
                int s = new Cursor(body).varInt();
                synchronized (lock) { if (s >= 0 && s <= 8) selected = s; }
            }
            case OFF_INV_SLOT -> invSlot(new Cursor(body));
            case OFF_OPEN -> {
                Cursor c = new Cursor(body);
                int id = c.varInt();
                c.varInt();
                String title = "";
                try {
                    title = BotSession.nbtRead(new DataInputStream(new ByteArrayInputStream(body, c.pos, body.length - c.pos)));
                } catch (Exception ignored) {}
                synchronized (lock) {
                    openId = id;
                    open = null;
                    openTitle = title;
                }
            }
            case OFF_CLOSE -> {
                int id = new Cursor(body).varInt();
                synchronized (lock) { if (id == openId) open = null; }
            }
            default -> {}
        }
    }

    private void content(Cursor c) throws IOException {
        int id = c.varInt();
        int state = c.varInt();
        int n = c.varInt();
        if (n < 0 || n > 200) throw new IOException("zla liczba slotow: " + n);
        Object[] arr = new Object[n];
        for (int i = 0; i < n; i++) arr[i] = reader.read(c);
        Object carried = reader.read(c);
        synchronized (lock) {
            if (id == 0) {
                inv = new Object[46];
                System.arraycopy(arr, 0, inv, 0, Math.min(n, 46));
                invState = state;
            } else {
                openId = id;
                open = arr;
                openState = state;
            }
            cursor = carried;
            desync = false;
        }
    }

    private void slot(Cursor c) throws IOException {
        int id = c.varInt();
        int state = c.varInt();
        int slot = c.s16();
        Object it = reader.read(c);
        synchronized (lock) {
            if (id == -1) {
                cursor = it;
            } else if (id == 0) {
                if (slot >= 0 && slot < 46) inv[slot] = it;
                invState = state;
            } else if (id == openId && open != null) {
                if (slot >= 0 && slot < open.length) open[slot] = it;
                openState = state;
            }
        }
    }

    private void invSlot(Cursor c) throws IOException {
        int s = c.varInt();
        Object it = reader.read(c);
        int w;
        if (s >= 0 && s <= 8) w = 36 + s;
        else if (s >= 9 && s <= 35) w = s;
        else if (s >= 36 && s <= 39) w = 44 - s;
        else if (s == 40) w = 45;
        else return;
        synchronized (lock) {
            inv[w] = it;
            if (open != null && s >= 0 && s <= 35) {
                int idx = s <= 8 ? open.length - 9 + s : open.length - 36 + (s - 9);
                if (idx >= 0 && idx < open.length) open[idx] = it;
            }
        }
    }

    // ------------------------------------------------------------------ odczyt dla GUI

    public boolean hasOpen() { synchronized (lock) { return open != null; } }
    public String openTitle() { synchronized (lock) { return openTitle; } }
    public int openSize() { synchronized (lock) { return open == null ? 0 : Math.max(0, open.length - 36); } }
    public Object openSlot(int i) { synchronized (lock) { return open != null && i >= 0 && i < open.length ? open[i] : null; } }
    public Object armor(int i) { synchronized (lock) { return inv[5 + i]; } }
    public Object offhand() { synchronized (lock) { return inv[45]; } }
    public Object craft(int i) { synchronized (lock) { return inv[1 + i]; } }
    public Object craftResult() { synchronized (lock) { return inv[0]; } }
    public Object cursor() { synchronized (lock) { return cursor; } }
    public int selected() { synchronized (lock) { return selected; } }
    public boolean isDesync() { synchronized (lock) { return desync; } }
    public boolean isHolding() { synchronized (lock) { return hold; } }

    public Object main(int i) {
        synchronized (lock) {
            int idx = open != null ? open.length - 36 + i : 9 + i;
            Object[] a = open != null ? open : inv;
            return idx >= 0 && idx < a.length ? a[idx] : null;
        }
    }

    public Object hotbar(int i) {
        synchronized (lock) {
            int idx = open != null ? open.length - 9 + i : 36 + i;
            Object[] a = open != null ? open : inv;
            return idx >= 0 && idx < a.length ? a[idx] : null;
        }
    }

    /** Zamienia (rodzaj slotu, indeks) na numer slotu w aktualnym oknie; -1 jesli to niemozliwe. */
    public int windowSlot(int kind, int idx) {
        synchronized (lock) {
            boolean o = open != null;
            return switch (kind) {
                case K_CONT -> o ? idx : -1;
                case K_MAIN -> o ? open.length - 36 + idx : 9 + idx;
                case K_HOT -> o ? open.length - 9 + idx : 36 + idx;
                case K_ARMOR -> o ? -1 : 5 + idx;
                case K_OFF -> o ? -1 : 45;
                case K_CRAFT -> o ? -1 : 1 + idx;
                case K_RES -> o ? -1 : 0;
                default -> -1;
            };
        }
    }

    // ------------------------------------------------------------------ akcje

    private void send(int id, byte[] body) {
        try {
            bot.sendRaw(id, body);
        } catch (IOException e) {
            bot.logLine(BotSession.RED, "Nie udalo sie wyslac akcji: " + e.getMessage());
        }
    }

    public void click(int slot, int button, int mode) {
        if (slot < 0) return;
        int wid, state;
        synchronized (lock) {
            wid = open != null ? openId : 0;
            state = open != null ? openState : invState;
        }
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(o);
            BotSession.writeVarInt(o, wid);
            BotSession.writeVarInt(o, state);
            d.writeShort(slot);
            d.writeByte(button);
            BotSession.writeVarInt(o, mode);
            BotSession.writeVarInt(o, 0);   // zmienione sloty (serwer i tak liczy sam)
            o.write(0);                     // przedmiot na kursorze: pusty
            send(S_CLICK, o.toByteArray());
        } catch (IOException ignored) {}
    }

    public void pickup(int slot, boolean right) { click(slot, right ? 1 : 0, 0); }
    public void quickMove(int slot) { click(slot, 0, 1); }
    public void swapHotbar(int slot, int hotbarIndex) { click(slot, hotbarIndex, 2); }
    public void throwItem(int slot, boolean all) { click(slot, all ? 1 : 0, 4); }

    public void select(int i) {
        if (i < 0 || i > 8) return;
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            new DataOutputStream(o).writeShort(i);
            send(S_HELD, o.toByteArray());
            synchronized (lock) { selected = i; }
        } catch (IOException ignored) {}
    }

    public void swing() {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            BotSession.writeVarInt(o, 0);
            send(S_SWING, o.toByteArray());
        } catch (IOException ignored) {}
    }

    public void useItem() {
        try {
            int seq;
            synchronized (lock) { seq = ++sequence; }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(o);
            BotSession.writeVarInt(o, 0);
            BotSession.writeVarInt(o, seq);
            d.writeFloat(bot.yaw());
            d.writeFloat(bot.pitch());
            send(S_USE, o.toByteArray());
        } catch (IOException ignored) {}
    }

    /** Wyrzuca przedmiot z aktualnego slotu w rece (jedna sztuke albo caly stos). */
    public void drop(boolean stack) {
        try {
            int seq;
            synchronized (lock) { seq = ++sequence; }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(o);
            BotSession.writeVarInt(o, stack ? 3 : 4); // 3 = caly stos, 4 = jedna sztuka
            d.writeLong(0L);
            d.writeByte(0);
            BotSession.writeVarInt(o, seq);
            send(S_ACTION, o.toByteArray());
        } catch (IOException ignored) {}
    }

    public void toggleHold() {
        boolean start;
        synchronized (lock) {
            hold = !hold;
            start = hold;
        }
        if (!start) return;
        Thread t = new Thread(() -> {
            while (isHolding() && bot.isInGame()) {
                useItem();
                try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            }
            synchronized (lock) { hold = false; }
        }, "BotMod-hold");
        t.setDaemon(true);
        t.start();
    }
}
