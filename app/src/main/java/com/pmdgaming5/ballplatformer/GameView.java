package com.pmdgaming5.ballplatformer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.*;

public final class GameView extends View {
    private static final int LEVELS = 32;
    private static final int ENDLESS = 33;
    private static final float PLAYER_R = 28f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences prefs;
    private final Random rng = new Random(1337);
    private final ArrayList<RectF> solids = new ArrayList<>();
    private final ArrayList<RectF> spikes = new ArrayList<>();
    private final ArrayList<RectF> springs = new ArrayList<>();
    private final ArrayList<Checkpoint> checkpoints = new ArrayList<>();
    private final ArrayList<Platform> platforms = new ArrayList<>();
    private final ArrayList<Gem> gems = new ArrayList<>();
    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Projectile> projectiles = new ArrayList<>();

    private int ui = 0;
    private int level = 1;
    private int unlocked;
    private int skin;
    private int totalGems;
    private int levelGems;
    private int deaths;
    private int endlessScore;
    private int bestEndless;
    private boolean muted;
    private boolean endlessMode;
    private boolean bossAlive;
    private boolean lifecyclePaused;
    private boolean left, right, jumpHeld, jumpPressed;
    private boolean grounded;
    private long groundedAt, jumpQueuedUntil, bossHitAt;
    private long elapsedMs, lastNs;
    private float x = 260, y = 650, vx, vy;
    private float cameraX, cameraY, worldW;
    private float checkpointX = 260, checkpointY = 650;
    private float bossX, bossY, bossHp, bossMaxHp, bossTimer;
    private float shake;
    private float segmentCursor;
    private int endlessSeed;
    private int currentTheme;

    public GameView(Context context) {
        super(context);
        prefs = context.getSharedPreferences("ball_platformer", Context.MODE_PRIVATE);
        unlocked = clampInt(prefs.getInt("unlocked", 1), 1, LEVELS);
        totalGems = Math.max(0, prefs.getInt("gems", 0));
        bestEndless = Math.max(0, prefs.getInt("best_endless", 0));
        skin = clampInt(prefs.getInt("skin", 0), 0, 2);
        muted = prefs.getBoolean("muted", false);
        stroke.setStyle(Paint.Style.STROKE);
        setFocusable(true);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        lastNs = System.nanoTime();
    }

    public void resumeGame() { lifecyclePaused = false; lastNs = System.nanoTime(); invalidate(); }
    public void pauseForLifecycle() { lifecyclePaused = true; Audio.stopAll(); }

    @Override protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = Math.min(0.033f, Math.max(0f, (now - lastNs) / 1_000_000_000f));
        lastNs = now;
        if (!lifecyclePaused && ui == 2) update(dt);
        render(canvas);
        postInvalidateOnAnimation();
    }

    private void update(float dt) {
        elapsedMs += (long)(dt * 1000f);
        updatePlatforms(dt);
        if (endlessMode) ensureEndlessWorld();
        movePlayer(dt);
        for (Enemy e : enemies) e.update(dt);
        for (Projectile p : projectiles) {
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 520f * dt; p.life -= dt;
        }
        projectiles.removeIf(p -> p.life <= 0f || p.x < cameraX - 600 || p.x > cameraX + getWidth() + 800);
        collectGems();
        checkHazards();
        updateCheckpoints();
        updateBoss(dt);
        updateParticles(dt);
        if (!endlessMode && !bossAlive && x >= worldW - 170) finishLevel();
        if (endlessMode) updateEndlessScore();
        cameraFollow(dt);
    }

    private void movePlayer(float dt) {
        if (left) vx -= 1650f * dt;
        if (right) vx += 1650f * dt;
        if (!left && !right) vx *= Math.pow(0.0008, dt);
        vx = clamp(vx, -650f, 650f);
        long now = SystemClock.uptimeMillis();
        if (jumpPressed) { jumpQueuedUntil = now + 140; jumpPressed = false; }
        if (now <= jumpQueuedUntil && (grounded || now - groundedAt < 120)) {
            vy = -1000f; grounded = false; jumpQueuedUntil = 0;
            burst(x, y + PLAYER_R, 9, 0xFFBDEBFF); Audio.play(Audio.JUMP, muted);
        }
        if (!jumpHeld && vy < -420f) vy += 1600f * dt;
        vy += 2450f * dt;
        vy = clamp(vy, -1150f, 1450f);
        float oldX = x, oldY = y;
        x += vx * dt; y += vy * dt;
        grounded = false;

        for (RectF s : solids) resolveRect(s, oldX, oldY);
        for (Platform m : platforms) {
            RectF r = m.rect();
            if (circleRect(x, y, PLAYER_R, r) && oldY + PLAYER_R <= r.top + 6f && y + PLAYER_R >= r.top) {
                y = r.top - PLAYER_R; vy = 0; grounded = true; groundedAt = now; x += m.dx; y += m.dy;
            }
        }
        if (y > 1350f) respawn();
    }

    private void resolveRect(RectF r, float oldX, float oldY) {
        if (!circleRect(x, y, PLAYER_R, r)) return;
        if (oldX + PLAYER_R <= r.left && x + PLAYER_R > r.left) { x = r.left - PLAYER_R; vx = -Math.abs(vx) * 0.08f; }
        else if (oldX - PLAYER_R >= r.right && x - PLAYER_R < r.right) { x = r.right + PLAYER_R; vx = Math.abs(vx) * 0.08f; }
        else if (oldY + PLAYER_R <= r.top && y + PLAYER_R > r.top) { y = r.top - PLAYER_R; vy = 0; grounded = true; groundedAt = SystemClock.uptimeMillis(); }
        else if (oldY - PLAYER_R >= r.bottom && y - PLAYER_R < r.bottom) { y = r.bottom + PLAYER_R; vy = Math.max(0f, vy); }
    }

    private void updatePlatforms(float dt) {
        for (Platform p : platforms) {
            float ox = p.x, oy = p.y;
            p.t += dt * p.speed;
            float z = (float)Math.sin(p.t) * p.range;
            if (p.axis == 0) p.x = p.baseX + z; else p.y = p.baseY + z;
            p.dx = p.x - ox; p.dy = p.y - oy;
        }
    }

    private void collectGems() {
        for (Gem g : gems) if (!g.collected && hit(x, y, PLAYER_R, g.x, g.y, g.r)) {
            g.collected = true; levelGems++; totalGems++; save(); burst(g.x, g.y, 12, 0xFFFFD166); Audio.play(Audio.COIN, muted);
        }
    }

    private void checkHazards() {
        for (RectF s : spikes) if (circleRect(x, y, PLAYER_R, s)) { respawn(); return; }
        for (RectF s : springs) if (circleRect(x, y, PLAYER_R, s) && vy >= 0) {
            y = s.top - PLAYER_R; vy = -1280f; grounded = false; burst(x, y + PLAYER_R, 12, 0xFF78E7FF); Audio.play(Audio.SPRING, muted);
        }
        for (Projectile q : projectiles) if (hit(x, y, PLAYER_R, q.x, q.y, q.r)) { respawn(); return; }
        for (Enemy e : enemies) if (hit(x, y, PLAYER_R, e.x, e.y, e.r)) { respawn(); return; }
    }

    private void updateCheckpoints() {
        for (Checkpoint c : checkpoints) if (!c.active && Math.abs(x - c.x) < 44 && Math.abs(y - c.y) < 100) {
            c.active = true; checkpointX = c.x; checkpointY = c.y - 58; burst(c.x, c.y - 55, 16, 0xFF7DFFB2); Audio.play(Audio.CHECK, muted);
        }
    }

    private void updateBoss(float dt) {
        if (!bossAlive) return;
        if (bossHp <= 0) { bossAlive = false; burst(bossX, bossY, 48, 0xFFFFD166); Audio.play(Audio.WIN, muted); return; }
        bossTimer += dt;
        bossX += (x - bossX) * Math.min(1f, dt * 1.35f);
        bossY = 590f + (float)Math.sin(bossTimer * 2.2f) * 42f;
        if (bossTimer > 1.2f) {
            bossTimer = 0f;
            float dx = x - bossX, dy = y - bossY, len = Math.max(1f, (float)Math.sqrt(dx * dx + dy * dy));
            projectiles.add(new Projectile(bossX, bossY, dx / len * 460f, dy / len * 460f, 15f));
            Audio.play(Audio.BOSS, muted);
        }
        long now = SystemClock.uptimeMillis();
        if (hit(x, y, PLAYER_R, bossX, bossY, 64f) && now - bossHitAt > 800) respawn();
        if (vy > 0 && y + PLAYER_R < bossY + 15 && y + PLAYER_R > bossY - 45 && Math.abs(x - bossX) < 75 && now - bossHitAt > 450) {
            bossHp -= 25f; vy = -820f; bossHitAt = now; burst(bossX, bossY, 18, 0xFFFFB347); Audio.play(Audio.COIN, muted);
        }
    }

    private void updateParticles(float dt) {
        for (Particle p : particles) { p.life -= dt; p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 500f * dt; p.vx *= 0.985f; }
        particles.removeIf(p -> p.life <= 0f);
    }

    private void updateEndlessScore() { endlessScore = Math.max(endlessScore, (int)((x - 260f) / 8f) + levelGems * 100); if (endlessScore > bestEndless) { bestEndless = endlessScore; save(); } }

    private void ensureEndlessWorld() {
        while (segmentCursor < x + getWidth() * 2.8f) generateEndlessSegment();
        solids.removeIf(r -> r.right < cameraX - 900);
        spikes.removeIf(r -> r.right < cameraX - 900);
        springs.removeIf(r -> r.right < cameraX - 900);
        platforms.removeIf(p -> p.x + p.w < cameraX - 900);
        gems.removeIf(g -> g.x < cameraX - 900 || g.collected);
        enemies.removeIf(e -> e.x < cameraX - 900);
    }

    private void generateEndlessSegment() {
        Random r = new Random(endlessSeed + (long)(segmentCursor * 17));
        float gap = 70f + r.nextInt(90);
        float w = 380f + r.nextInt(330);
        float top = 710f - r.nextInt(180);
        float start = segmentCursor + gap;
        solids.add(new RectF(start, top, start + w, top + 140));
        if (r.nextFloat() < 0.62f) gems.add(new Gem(start + w * 0.5f, top - 68, r.nextInt()));
        if (r.nextFloat() < 0.48f) spikes.add(new RectF(start + w * 0.65f, top - 25, start + w * 0.65f + 46, top + 18));
        if (r.nextFloat() < 0.25f) springs.add(new RectF(start + w * 0.22f, top - 28, start + w * 0.22f + 70, top + 2));
        if (r.nextFloat() < 0.42f) enemies.add(new Enemy(start + w * 0.78f, top - 30, r.nextInt(3), 90f + r.nextInt(80)));
        if (r.nextFloat() < 0.28f) platforms.add(new Platform(start + w * 0.35f, top - 150f, 150f, 24f, 45f, 0.9f + r.nextFloat() * 0.7f, r.nextBoolean() ? 0 : 1));
        segmentCursor = start + w;
    }

    private void finishLevel() {
        ui = 4;
        int stars = stars();
        if (level < LEVELS) unlocked = Math.max(unlocked, level + 1);
        save(); Audio.stopAll(); Audio.play(Audio.WIN, muted); burst(x, y, 40, 0xFFFFD166);
    }

    private int stars() {
        int stars = 1;
        if (deaths <= 3) stars++;
        if (levelGems >= 3) stars++;
        return Math.min(3, stars);
    }

    private void respawn() {
        deaths++; x = checkpointX; y = checkpointY; vx = vy = 0; shake = 0.45f; projectiles.clear(); burst(x, y, 16, 0xFFFF6B6B); Audio.play(Audio.HURT, muted);
    }

    private void cameraFollow(float dt) {
        float tx = x - getWidth() * 0.34f;
        float ty = y - getHeight() * 0.58f;
        cameraX += (tx - cameraX) * Math.min(1f, dt * 5f);
        cameraY += (ty - cameraY) * Math.min(1f, dt * 5f);
        float maxX = endlessMode ? Math.max(0f, segmentCursor - getWidth() * 0.7f) : Math.max(0f, worldW - getWidth());
        cameraX = clamp(cameraX, 0f, maxX);
        cameraY = clamp(cameraY, -100f, 420f);
        shake *= 0.88f;
    }

    private void startLevel(int id) {
        level = id; endlessMode = false; elapsedMs = 0; deaths = 0; levelGems = 0; endlessScore = 0;
        ui = 2; bossAlive = false; cameraX = cameraY = 0; checkpointX = 260; checkpointY = 650; x = checkpointX; y = checkpointY; vx = vy = 0; shake = 0;
        clearWorld(); buildLevel(id); Audio.music(id, muted);
    }

    private void startEndless() {
        endlessMode = true; level = ENDLESS; elapsedMs = 0; deaths = 0; levelGems = 0; endlessScore = 0; ui = 2; bossAlive = false;
        cameraX = cameraY = 0; checkpointX = 260; checkpointY = 650; x = checkpointX; y = checkpointY; vx = vy = 0; segmentCursor = 0; endlessSeed = (int)(System.currentTimeMillis() & 0x7fffffff);
        clearWorld(); solids.add(new RectF(0, 820, 820, 120)); segmentCursor = 820; generateEndlessSegment(); Audio.music(0, muted);
    }

    private void clearWorld() { solids.clear(); spikes.clear(); springs.clear(); checkpoints.clear(); platforms.clear(); gems.clear(); enemies.clear(); particles.clear(); projectiles.clear(); }

    private void buildLevel(int id) {
        int world = (id - 1) / 8;
        int variant = (id - 1) % 8;
        currentTheme = world;
        worldW = 6000f + world * 500f + variant * 170f;
        float cur = 0;
        Random r = new Random(id * 977);
        solids.add(new RectF(0, 800, 820, 150));
        cur = 750;
        for (int i = 0; i < 17 + world; i++) {
            float gap = 65f + r.nextInt(100);
            float width = 340f + r.nextInt(290);
            float top = 775f - (i % 3) * 55f - (variant % 3) * 10f;
            cur += gap;
            solids.add(new RectF(cur, top, cur + width, top + 140));
            if (i % 2 == 0) gems.add(new Gem(cur + width * 0.45f, top - 70, i * 31));
            if ((i + variant) % 3 == 1) spikes.add(new RectF(cur + width * 0.67f, top - 24, cur + width * 0.67f + 48, top + 18));
            if ((i + world) % 5 == 2) springs.add(new RectF(cur + width * 0.20f, top - 27, cur + width * 0.20f + 72, top + 3));
            if ((i + id) % 3 != 0) enemies.add(new Enemy(cur + width * 0.76f, top - 30, (i + id) % 3, 90f + (id % 5) * 12f));
            if (i % 4 == 2) platforms.add(new Platform(cur + width * 0.32f, top - 150, 155, 24, 55 + (i % 2) * 20, 0.8f + (i % 3) * .2f, i % 2));
            if (i == 5 || i == 11) checkpoints.add(new Checkpoint(cur + width * 0.2f, top));
            cur += width;
        }
        while (cur < worldW - 500) {
            cur += 90;
            solids.add(new RectF(cur, 815, 470, 135));
            if (((int)cur / 100) % 2 == 0) gems.add(new Gem(cur + 210, 740, (int)cur));
            cur += 470;
        }
        solids.add(new RectF(worldW - 580, 560, worldW - 260, 610));
        solids.add(new RectF(worldW - 260, 690, worldW, 940));
        if (id % 8 == 0) {
            bossAlive = true; bossX = worldW - 620; bossY = 610; bossMaxHp = 125f + world * 35f; bossHp = bossMaxHp;
        }
    }

    private void render(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (ui == 0) menu(c, w, h); else if (ui == 1) select(c, w, h); else if (ui == 5) settings(c, w, h); else { world(c, w, h); if (ui == 3) pause(c, w, h); if (ui == 4) win(c, w, h); }
    }

    private void world(Canvas c, int w, int h) {
        int top = currentTheme == 1 ? 0xFF0E2134 : currentTheme == 2 ? 0xFF24143A : currentTheme == 3 ? 0xFF1A2430 : 0xFF071225;
        int bottom = currentTheme == 1 ? 0xFF255C79 : currentTheme == 2 ? 0xFF7A3D6B : currentTheme == 3 ? 0xFF526A77 : 0xFF1B6073;
        paint.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, h, paint); paint.setShader(null);
        drawParallax(c, w, h, .13f, currentTheme == 2 ? 0xFF392044 : 0xFF10324A, 105);
        drawParallax(c, w, h, .25f, currentTheme == 3 ? 0xFF364B57 : 0xFF17485C, 170);
        c.save(); c.translate(-cameraX, -cameraY);
        if (shake > .01f) c.translate((float)Math.sin(elapsedMs * .03) * shake * 10f, (float)Math.cos(elapsedMs * .02) * shake * 10f);
        for (RectF r : solids) drawSolid(c, r);
        for (Platform p : platforms) drawPlatform(c, p);
        for (RectF r : springs) drawSpring(c, r);
        for (RectF r : spikes) drawSpike(c, r);
        for (Checkpoint cp : checkpoints) drawCheckpoint(c, cp);
        for (Gem g : gems) if (!g.collected) drawGem(c, g);
        for (Enemy e : enemies) e.draw(c);
        for (Projectile q : projectiles) drawProjectile(c, q);
        if (bossAlive) drawBoss(c);
        if (!endlessMode) drawGoal(c); else drawEndlessMarker(c);
        for (Particle q : particles) drawParticle(c, q);
        drawBall(c);
        c.restore();
        drawHud(c, w, h);
    }

    private void drawParallax(Canvas c, int w, int h, float f, int color, int bh) {
        paint.setColor(color); Path path = new Path(); float off = -(cameraX * f) % 420f; path.moveTo(0, h);
        for (int ix = -500; ix < w + 700; ix += 140) path.lineTo(ix + off, h - bh - (float)Math.sin((ix + cameraX * f) * .012) * 35);
        path.lineTo(w, h); path.close(); c.drawPath(path, paint);
    }

    private void drawSolid(Canvas c, RectF r) {
        paint.setColor(0xFF17374B); c.drawRoundRect(r, 12, 12, paint);
        paint.setColor(currentTheme == 1 ? 0xFF65A6AE : currentTheme == 2 ? 0xFFC18AE2 : 0xFF4C98A0); c.drawRoundRect(r.left, r.top, r.right, r.top + 14, 10, 10, paint);
        paint.setColor(0x332AFFFF); c.drawRect(r.left + 8, r.top + 14, r.right - 8, r.top + 18, paint);
    }

    private void drawPlatform(Canvas c, Platform p) { paint.setColor(0xFF244F64); c.drawRoundRect(p.rect(), 10, 10, paint); paint.setColor(0xFF78D7D9); c.drawRoundRect(p.x + 6, p.y + 4, p.x + p.w - 6, p.y + 8, 4, 4, paint); }
    private void drawSpring(Canvas c, RectF r) { paint.setColor(0xFF2B3A50); c.drawRoundRect(r, 8, 8, paint); paint.setColor(0xFF7CE7FF); c.drawRoundRect(r.left + 6, r.top + 4, r.right - 6, r.top + 10, 4, 4, paint); }
    private void drawSpike(Canvas c, RectF r) { paint.setColor(0xFFFF6E72); Path p = new Path(); p.moveTo(r.left, r.bottom); p.lineTo(r.centerX(), r.top); p.lineTo(r.right, r.bottom); p.close(); c.drawPath(p, paint); }
    private void drawCheckpoint(Canvas c, Checkpoint q) { paint.setColor(0xFFBCECF0); c.drawRect(q.x, q.y - 70, q.x + 4, q.y, paint); paint.setColor(q.active ? 0xFF7DFFB2 : 0xFF8198AA); Path p = new Path(); p.moveTo(q.x + 4, q.y - 70); p.lineTo(q.x + 52, q.y - 54); p.lineTo(q.x + 4, q.y - 38); p.close(); c.drawPath(p, paint); }
    private void drawGem(Canvas c, Gem g) { float k = 1f + (float)Math.sin((elapsedMs + g.phase) * .006f) * .1f; c.save(); c.translate(g.x, g.y); c.scale(k, k); paint.setShadowLayer(16, 0, 0, 0x66FFD166); paint.setColor(0xFFFFD166); Path p = new Path(); p.moveTo(0, -g.r); p.lineTo(g.r * .8f, 0); p.lineTo(0, g.r); p.lineTo(-g.r * .8f, 0); p.close(); c.drawPath(p, paint); paint.clearShadowLayer(); paint.setColor(0xFFFFF6C8); c.drawCircle(-5, -6, 3, paint); c.restore(); }
    private void drawGoal(Canvas c) { paint.setShadowLayer(24, 0, 0, 0x887DFFB2); paint.setColor(0xFF7DFFB2); c.drawCircle(worldW - 105, 640, 42, paint); paint.clearShadowLayer(); paint.setColor(0xFF0B1624); c.drawCircle(worldW - 105, 640, 24, paint); }
    private void drawEndlessMarker(Canvas c) { float px = Math.max(segmentCursor - 220, x + 250); paint.setColor(0xFF7CE7FF); c.drawRoundRect(px, 570, px + 180, 600, 15, 15, paint); }
    private void drawProjectile(Canvas c, Projectile q) { paint.setShadowLayer(14, 0, 0, 0x88FF7043); paint.setColor(0xFFFF7043); c.drawCircle(q.x, q.y, q.r, paint); paint.clearShadowLayer(); }
    private void drawBoss(Canvas c) { paint.setShadowLayer(26, 0, 10, 0xAAFF4F5A); paint.setColor(0xFF8E3A54); c.drawCircle(bossX, bossY, 66, paint); paint.clearShadowLayer(); paint.setColor(0xFFFFB347); c.drawCircle(bossX - 18, bossY - 8, 10, paint); c.drawCircle(bossX + 18, bossY - 8, 10, paint); paint.setColor(0xFF2D1532); c.drawCircle(bossX - 18, bossY - 8, 4, paint); c.drawCircle(bossX + 18, bossY - 8, 4, paint); paint.setColor(0xFF1A2130); c.drawRoundRect(bossX - 82, bossY - 97, bossX + 82, bossY - 82, 8, 8, paint); paint.setColor(0xFF7DFFB2); c.drawRoundRect(bossX - 80, bossY - 95, bossX - 80 + 160 * (bossHp / bossMaxHp), bossY - 85, 5, 5, paint); }
    private void drawParticle(Canvas c, Particle q) { paint.setAlpha((int)(255 * Math.max(0, q.life / q.max))); paint.setColor(q.color); c.drawCircle(q.x, q.y, q.size, paint); paint.setAlpha(255); }

    private void drawBall(Canvas c) {
        int[] colors = skin == 1 ? new int[]{Color.WHITE, 0xFFFFE0EF, 0xFFE05A93, 0xFF8F214F} : skin == 2 ? new int[]{Color.WHITE, 0xFFE8FFD0, 0xFF62C15B, 0xFF17572B} : new int[]{Color.WHITE, 0xFFE5F4FF, 0xFF3B93D0, 0xFF11517F};
        c.save(); c.translate(x, y); paint.setShader(new RadialGradient(-10, -12, 44, colors, new float[]{0f, .12f, .65f, 1f}, Shader.TileMode.CLAMP)); paint.setShadowLayer(18, 0, 12, 0x88000000); c.drawCircle(0, 0, PLAYER_R, paint); paint.clearShadowLayer(); paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5); paint.setColor(0xFFB8E3FF); c.drawArc(-PLAYER_R + 5, -PLAYER_R + 5, PLAYER_R - 5, PLAYER_R - 5, vx * 12f, 150, false, paint); paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFF9FDFF); c.drawCircle(-10, -11, 7, paint); c.restore();
    }

    private void drawHud(Canvas c, int w, int h) {
        paint.setColor(0xAA071225); c.drawRoundRect(18, 16, 350, 68, 18, 18, paint); paint.setColor(Color.WHITE); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(20); c.drawText(endlessMode ? "ENDLESS" : "LEVEL " + level, 34, 49, paint); paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(16); c.drawText("◆ " + levelGems, 165, 49, paint); if (endlessMode) c.drawText("BEST " + bestEndless, 238, 49, paint); else c.drawText(starsText(), 250, 49, paint);
        button(c, w - 68, 42, 54, 48, "Ⅱ", 0x553A5877);
        float cy = h - 94; button(c, 80, cy, 66, 58, "‹", 0x553A5877); button(c, 162, cy, 66, 58, "›", 0x553A5877); button(c, w - 90, cy, 78, 58, "↑", 0x557DFFB2);
    }

    private String starsText() { int s = stars(); return "★".repeat(s) + "☆".repeat(3 - s); }

    private void menu(Canvas c, int w, int h) {
        paint.setShader(new LinearGradient(0, 0, w, h, 0xFF071225, 0xFF16445F, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, h, paint); paint.setShader(null);
        float cx = w * .5f, cy = h * .29f; drawLogo(c, cx, cy, 80); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setColor(Color.WHITE); paint.setTextSize(44); c.drawText("BALL", cx, cy + 132, paint); paint.setColor(0xFF7CE7FF); paint.setTextSize(38); c.drawText("PLATFORMER", cx, cy + 174, paint);
        button(c, cx, cy + 255, 260, 62, "PLAY", 0xAA2D76A3); button(c, cx, cy + 330, 260, 58, "LEVEL SELECT", 0x553A5877); button(c, cx, cy + 400, 260, 58, "ENDLESS MODE", 0x553A5877); button(c, cx + 160, cy + 330, 78, 58, "⚙", 0x553A5877);
        paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(13); paint.setColor(0x99FFFFFF); c.drawText("32 handcrafted-style levels • bosses • checkpoints • endless", cx, h - 22, paint); paint.setTextAlign(Paint.Align.LEFT);
    }

    private void select(Canvas c, int w, int h) {
        paint.setColor(0xFF081526); c.drawRect(0, 0, w, h, paint); paint.setColor(Color.WHITE); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(34); c.drawText("SELECT LEVEL", 28, 52, paint); paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(15); paint.setColor(0xAAFFFFFF); c.drawText("Unlocked " + unlocked + " / " + LEVELS, 30, 78, paint);
        float gx = w / 8.5f; for (int i = 1; i <= LEVELS; i++) { int rr = (i - 1) / 8, cc = (i - 1) % 8; float px = 54 + cc * gx, py = 124 + rr * 70; boolean open = i <= unlocked; paint.setColor(open ? 0xFF22536A : 0xFF26313F); c.drawRoundRect(px - 39, py - 23, px + 39, py + 23, 13, 13, paint); paint.setTextAlign(Paint.Align.CENTER); paint.setColor(open ? 0xFF7CE7FF : 0xFF78818B); paint.setTextSize(20); c.drawText(String.valueOf(i), px, py + 7, paint); }
        paint.setTextAlign(Paint.Align.LEFT); button(c, w - 70, h - 44, 110, 54, "BACK", 0x553A5877);
    }

    private void pause(Canvas c, int w, int h) {
        overlay(c); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(42); paint.setColor(Color.WHITE); c.drawText("PAUSED", w / 2f, h * .28f, paint);
        button(c, w / 2f, h * .49f, 220, 58, "RESUME", 0xAA2D76A3); button(c, w / 2f, h * .62f, 220, 58, "RESTART", 0x553A5877); button(c, w / 2f, h * .75f, 220, 58, "LEVEL SELECT", 0x553A5877); paint.setTextAlign(Paint.Align.LEFT);
    }

    private void win(Canvas c, int w, int h) {
        overlay(c); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(42); paint.setColor(0xFFFFD166); c.drawText(endlessMode ? "ENDLESS RUN" : (level == LEVELS ? "WORLD COMPLETE!" : "LEVEL COMPLETE!"), w / 2f, h * .26f, paint);
        paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(20); paint.setColor(Color.WHITE); if (endlessMode) c.drawText("Score  " + endlessScore, w / 2f, h * .38f, paint); else { c.drawText("Time  " + formatTime(elapsedMs), w / 2f, h * .36f, paint); c.drawText(starsText(), w / 2f, h * .43f, paint); }
        button(c, w / 2f, h * .58f, 250, 60, endlessMode ? "RUN AGAIN" : (level < LEVELS ? "NEXT LEVEL" : "PLAY AGAIN"), 0xAA2D76A3); button(c, w / 2f, h * .71f, 250, 56, "LEVEL SELECT", 0x553A5877); paint.setTextAlign(Paint.Align.LEFT);
    }

    private void settings(Canvas c, int w, int h) {
        paint.setColor(0xFF081526); c.drawRect(0, 0, w, h, paint); paint.setColor(Color.WHITE); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(38); c.drawText("SETTINGS", 30, 60, paint); paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(20); c.drawText("Sound", 38, 138, paint); button(c, 230, 128, 150, 52, muted ? "OFF" : "ON", muted ? 0x553A5877 : 0xAA2D76A3); c.drawText("Ball skin", 38, 215, paint); button(c, 230, 205, 150, 52, skin == 0 ? "BLUE" : skin == 1 ? "PINK" : "GREEN", 0x553A5877); c.drawText("Lifetime gems: " + totalGems, 38, 290, paint); c.drawText("Best endless score: " + bestEndless, 38, 325, paint); button(c, 230, 410, 210, 56, "BACK", 0x553A5877);
    }

    private void overlay(Canvas c) { paint.setColor(0xAA050B13); c.drawRect(0, 0, getWidth(), getHeight(), paint); }

    private void button(Canvas c, float cx, float cy, float w, float h, String text, int color) { paint.setColor(color); c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 18, 18, paint); stroke.setColor(0x66FFFFFF); stroke.setStrokeWidth(2); c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 18, 18, stroke); paint.setColor(Color.WHITE); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(h * .38f); c.drawText(text, cx, cy + h * .13f, paint); paint.setTextAlign(Paint.Align.LEFT); }

    private void drawLogo(Canvas c, float x, float y, float r) { paint.setShader(new RadialGradient(x - 18, y - 22, r, new int[]{Color.WHITE, 0xFFBFE9FF, 0xFF4A9DD7, 0xFF0E4771}, new float[]{0f, .1f, .64f, 1f}, Shader.TileMode.CLAMP)); c.drawCircle(x, y, r, paint); paint.setShader(null); paint.setColor(0xFF193B55); c.drawCircle(x - 18, y - 20, 9, paint); c.drawCircle(x + 18, y - 20, 9, paint); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float tx = e.getX(), ty = e.getY(); int action = e.getActionMasked(); boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_POINTER_DOWN; boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_UP;
        if (ui == 0 && action == MotionEvent.ACTION_UP) {
            float cy = getHeight() * .29f, cx = getWidth() / 2f;
            if (hitRect(tx, ty, cx, cy + 255, 280, 72)) { startLevel(unlocked); return true; }
            if (hitRect(tx, ty, cx, cy + 330, 280, 70)) { ui = 1; return true; }
            if (hitRect(tx, ty, cx, cy + 400, 280, 70)) { startEndless(); return true; }
            if (hitRect(tx, ty, cx + 160, cy + 330, 100, 70)) { ui = 5; return true; }
            return true;
        }
        if (ui == 1 && action == MotionEvent.ACTION_UP) {
            float gx = getWidth() / 8.5f;
            for (int i = 1; i <= LEVELS; i++) { int rr = (i - 1) / 8, cc = (i - 1) % 8; float px = 54 + cc * gx, py = 124 + rr * 70; if (i <= unlocked && hitRect(tx, ty, px, py, 92, 58)) { startLevel(i); return true; } }
            if (hitRect(tx, ty, getWidth() - 70, getHeight() - 44, 125, 70)) { ui = 0; return true; }
            return true;
        }
        if (ui == 5 && action == MotionEvent.ACTION_UP) {
            if (hitRect(tx, ty, 230, 128, 180, 68)) { muted = !muted; save(); return true; }
            if (hitRect(tx, ty, 230, 205, 180, 68)) { skin = (skin + 1) % 3; save(); return true; }
            if (hitRect(tx, ty, 230, 410, 240, 70)) { ui = 0; return true; }
            return true;
        }
        if (ui == 3 && action == MotionEvent.ACTION_UP) {
            if (hitRect(tx, ty, getWidth() / 2f, getHeight() * .49f, 250, 72)) { ui = 2; Audio.music(endlessMode ? 0 : level, muted); return true; }
            if (hitRect(tx, ty, getWidth() / 2f, getHeight() * .62f, 250, 72)) { if (endlessMode) startEndless(); else startLevel(level); return true; }
            if (hitRect(tx, ty, getWidth() / 2f, getHeight() * .75f, 250, 72)) { ui = 1; Audio.stopAll(); return true; }
            return true;
        }
        if (ui == 4 && action == MotionEvent.ACTION_UP) {
            if (hitRect(tx, ty, getWidth() / 2f, getHeight() * .58f, 270, 74)) { if (endlessMode) startEndless(); else startLevel(level < LEVELS ? level + 1 : 1); return true; }
            if (hitRect(tx, ty, getWidth() / 2f, getHeight() * .71f, 270, 70)) { ui = 1; return true; }
            return true;
        }
        if (ui == 2) {
            if (ty < 95 && tx > getWidth() - 130 && action == MotionEvent.ACTION_UP) { ui = 3; Audio.stopAll(); return true; }
            if (ty > getHeight() - 175) {
                if (tx < 125) left = down;
                else if (tx < 260) right = down;
                else if (tx > getWidth() - 170) { if (down) jumpPressed = true; jumpHeld = down; }
                if (up && tx < 125) left = false;
                if (up && tx >= 125 && tx < 260) right = false;
                if (up && tx > getWidth() - 170) jumpHeld = false;
            }
            return true;
        }
        return true;
    }

    private boolean hitRect(float x, float y, float cx, float cy, float w, float h) { return Math.abs(x - cx) <= w / 2f && Math.abs(y - cy) <= h / 2f; }
    private static boolean circleRect(float cx, float cy, float r, RectF q) { float nx = clamp(cx, q.left, q.right), ny = clamp(cy, q.top, q.bottom); float dx = cx - nx, dy = cy - ny; return dx * dx + dy * dy <= r * r; }
    private static boolean hit(float ax, float ay, float ar, float bx, float by, float br) { float dx = ax - bx, dy = ay - by, rr = ar + br; return dx * dx + dy * dy <= rr * rr; }
    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    private static int clampInt(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }
    private static String formatTime(long ms) { return String.format(Locale.US, "%02d:%02d", (ms / 1000) / 60, (ms / 1000) % 60); }
    private void burst(float px, float py, int count, int color) { for (int i = 0; i < count; i++) { double a = rng.nextDouble() * Math.PI * 2; float sp = 50f + rng.nextFloat() * 290f; particles.add(new Particle(px, py, (float)Math.cos(a) * sp, (float)Math.sin(a) * sp - 50f, 2f + rng.nextFloat() * 5f, color, .35f + rng.nextFloat() * .55f)); } }
    private void save() { prefs.edit().putInt("unlocked", unlocked).putInt("gems", totalGems).putInt("best_endless", bestEndless).putInt("skin", skin).putBoolean("muted", muted).apply(); }

    private static final class Gem { final float x, y, r = 17f; final int phase; boolean collected; Gem(float x, float y, int phase) { this.x = x; this.y = y; this.phase = phase; } }
    private static final class Checkpoint { final float x, y; boolean active; Checkpoint(float x, float y) { this.x = x; this.y = y; } }
    private static final class Particle { float x, y, vx, vy, size, life, max; final int color; Particle(float x, float y, float vx, float vy, float size, int color, float life) { this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.size=size;this.color=color;this.life=life;this.max=life; } }
    private static final class Projectile { float x,y,vx,vy,r,life=5f; Projectile(float x,float y,float vx,float vy,float r){this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.r=r;} }
    private static final class Platform { final float baseX,baseY,w,h,range,speed; float x,y,t,dx,dy; final int axis; Platform(float x,float y,float w,float h,float range,float speed,int axis){baseX=this.x=x;baseY=this.y=y;this.w=w;this.h=h;this.range=range;this.speed=speed;this.axis=axis;} RectF rect(){return new RectF(x,y,x+w,y+h);} }
    private final class Enemy { float x,y,baseY,speed,t; final float min,max,r=26f; final int type; int dir=1; Enemy(float x,float y,int type,float speed){this.x=x;this.y=y;baseY=y;this.type=type;this.speed=speed;min=x-120;max=x+120;} void update(float dt){t+=dt;if(type==0){x+=dir*speed*dt;if(x<min||x>max)dir*=-1;} else if(type==1){x+=(float)Math.sin(t*2.4)*speed*.45f*dt;y=baseY+(float)Math.sin(t*3.1)*42f;} else {x+=dir*speed*dt;y=baseY-(float)Math.abs(Math.sin(t*2.8))*36f;if(x<min||x>max)dir*=-1;}} void draw(Canvas c){paint.setColor(type==0?0xFFFF6B6B:type==1?0xFF9E8CFF:0xFFFF9D4D);c.drawCircle(x,y,r,paint);paint.setColor(Color.WHITE);c.drawCircle(x-7,y-6,4,paint);c.drawCircle(x+7,y-6,4,paint);paint.setColor(0xFF1B2230);c.drawCircle(x-7+dir*2,y-6,2,paint);c.drawCircle(x+7+dir*2,y-6,2,paint);}}

    private static final class Audio {
        static final int JUMP=1, COIN=2, HURT=3, WIN=4, SPRING=5, CHECK=6, BOSS=7;
        private static final int RATE=22050;
        private static volatile Thread musicThread;
        private static volatile boolean musicRunning;
        static void play(int type, boolean muted) { if (muted) return; float f=type==JUMP?660:type==COIN?960:type==HURT?180:type==WIN?780:type==SPRING?520:type==CHECK?880:240; float d=type==WIN?.45f:.11f; new Thread(() -> tone(f,d,type==HURT?.20f:.14f), "sfx").start(); }
        static void music(int level, boolean muted) { stopAll(); if (muted) return; musicRunning=true; musicThread=new Thread(() -> { float base=level%8==0?196f:level%5==0?233f:261.6f; float[] notes={base,base*1.25f,base*1.5f,base*2f,base*1.5f,base*1.25f,base*1.12f,base*1.5f}; while(musicRunning){for(float n:notes){if(!musicRunning)break;tone(n,.10f,.028f);}} }, "music"); musicThread.start(); }
        static void stopAll(){musicRunning=false;if(musicThread!=null){musicThread.interrupt();musicThread=null;}}
        private static void tone(float f,float d,float vol){int samples=(int)(RATE*d);short[] data=new short[samples];for(int i=0;i<samples;i++){float t=i/(float)RATE;float env=Math.min(1f,t*30f)*Math.min(1f,(d-t)*16f);data[i]=(short)(Math.sin(6.2831853f*f*t)*32767f*vol*env);}AudioTrack track=null;try{track=new AudioTrack(AudioManager.STREAM_MUSIC,RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(256,samples*2),AudioTrack.MODE_STATIC);track.write(data,0,data.length);track.play();Thread.sleep((long)(d*1000)+18);}catch(Exception ignored){}finally{if(track!=null){try{track.stop();}catch(Exception ignored){}track.release();}}}
    }
}