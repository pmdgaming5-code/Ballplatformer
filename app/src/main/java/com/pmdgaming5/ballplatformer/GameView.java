package com.pmdgaming5.ballplatformer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;

public final class GameView extends View {
    private static final int LEVELS = 32;
    private static final int ENDLESS = 33;
    private static final float BALL_R = 30f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences prefs;

    private final ArrayList<RectF> solids = new ArrayList<>();
    private final ArrayList<RectF> spikes = new ArrayList<>();
    private final ArrayList<RectF> springs = new ArrayList<>();
    private final ArrayList<Checkpoint> checkpoints = new ArrayList<>();
    private final ArrayList<Gem> gems = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Projectile> projectiles = new ArrayList<>();
    private final ArrayList<Platform> platforms = new ArrayList<>();
    private final ArrayList<Enemy> enemies = new ArrayList<>();

    private final Random particlesRandom = new Random(1337);

    private int ui = 0;
    private int level = 1;
    private int unlocked;
    private int totalGems;
    private int levelGems;
    private int deaths;
    private int skin;
    private int bestEndless;
    private int endlessScore;
    private int theme;
    private boolean muted;
    private boolean endlessMode;
    private boolean bossAlive;
    private boolean lifecyclePaused;

    private int leftPointer = -1;
    private int rightPointer = -1;
    private int jumpPointer = -1;

    private boolean jumpQueued;
    private boolean grounded;
    private long groundedAt;
    private long jumpQueueUntil;
    private long elapsedMs;
    private long lastNs;
    private long bossHitAt;

    private float x = 260f;
    private float y = 650f;
    private float vx;
    private float vy;
    private float cameraX;
    private float cameraY;
    private float worldW;
    private float checkpointX = 260f;
    private float checkpointY = 650f;
    private float bossX;
    private float bossY;
    private float bossHp;
    private float bossMaxHp;
    private float bossTimer;
    private float segmentCursor;
    private float cameraShake;
    private int endlessSeed;

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

    public void resumeGame() {
        lifecyclePaused = false;
        lastNs = System.nanoTime();
        invalidate();
    }

    public void pauseForLifecycle() {
        lifecyclePaused = true;
        clearPointers();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = Math.min(0.033f, Math.max(0f, (now - lastNs) / 1_000_000_000f));
        lastNs = now;
        if (!lifecyclePaused && ui == 2) update(dt);
        render(canvas);
        postInvalidateOnAnimation();
    }

    private void update(float dt) {
        elapsedMs += (long) (dt * 1000f);
        updatePlatforms(dt);
        if (endlessMode) ensureEndlessWorld();
        updatePlayer(dt);
        updateEnemies(dt);
        updateProjectiles(dt);
        collectGems();
        checkHazards();
        checkCheckpoints();
        updateBoss(dt);
        updateParticles(dt);

        if (!endlessMode && !bossAlive && x >= worldW - 180f) finishLevel();
        if (endlessMode) {
            endlessScore = Math.max(endlessScore, (int) ((x - 260f) / 7f) + levelGems * 100);
            if (endlessScore > bestEndless) {
                bestEndless = endlessScore;
                save();
            }
        }
        followCamera(dt);
    }

    private void updatePlayer(float dt) {
        boolean left = leftPointer >= 0;
        boolean right = rightPointer >= 0;
        boolean jumpHeld = jumpPointer >= 0;

        if (left) vx -= 1800f * dt;
        if (right) vx += 1800f * dt;
        if (!left && !right) vx *= (float) Math.pow(0.0009, dt);
        vx = clamp(vx, -700f, 700f);

        long now = android.os.SystemClock.uptimeMillis();
        if (jumpQueued) {
            jumpQueueUntil = now + 150L;
            jumpQueued = false;
        }
        if (now <= jumpQueueUntil && (grounded || now - groundedAt <= 130L)) {
            vy = -1030f;
            grounded = false;
            jumpQueueUntil = 0L;
            burst(x, y + BALL_R, 10, 0xFFBDEBFF);
        }

        if (!jumpHeld && vy < -420f) vy += 1800f * dt;
        vy += 2450f * dt;
        vy = clamp(vy, -1200f, 1500f);

        float oldX = x;
        float oldY = y;
        x += vx * dt;
        y += vy * dt;
        grounded = false;

        for (RectF solid : solids) resolveSolid(solid, oldX, oldY);
        for (Platform platform : platforms) {
            RectF r = platform.rect();
            if (circleRect(x, y, BALL_R, r) && oldY + BALL_R <= r.top + 7f && y + BALL_R >= r.top) {
                y = r.top - BALL_R;
                vy = 0f;
                grounded = true;
                groundedAt = now;
                x += platform.dx;
                y += platform.dy;
            }
        }
        if (y > 1450f) respawn();
    }

    private void resolveSolid(RectF solid, float oldX, float oldY) {
        if (!circleRect(x, y, BALL_R, solid)) return;
        if (oldX + BALL_R <= solid.left && x + BALL_R > solid.left) {
            x = solid.left - BALL_R;
            vx = -Math.abs(vx) * 0.12f;
        } else if (oldX - BALL_R >= solid.right && x - BALL_R < solid.right) {
            x = solid.right + BALL_R;
            vx = Math.abs(vx) * 0.12f;
        } else if (oldY + BALL_R <= solid.top && y + BALL_R > solid.top) {
            y = solid.top - BALL_R;
            vy = 0f;
            grounded = true;
            groundedAt = android.os.SystemClock.uptimeMillis();
        } else if (oldY - BALL_R >= solid.bottom && y - BALL_R < solid.bottom) {
            y = solid.bottom + BALL_R;
            vy = Math.max(0f, vy);
        }
    }

    private void updatePlatforms(float dt) {
        for (Platform platform : platforms) {
            float oldX = platform.x;
            float oldY = platform.y;
            platform.t += dt * platform.speed;
            float wave = (float) Math.sin(platform.t) * platform.range;
            if (platform.axis == 0) platform.x = platform.baseX + wave;
            else platform.y = platform.baseY + wave;
            platform.dx = platform.x - oldX;
            platform.dy = platform.y - oldY;
        }
    }

    private void updateEnemies(float dt) {
        for (Enemy enemy : enemies) if (enemy.alive) enemy.update(dt);
    }

    private void updateProjectiles(float dt) {
        Iterator<Projectile> iterator = projectiles.iterator();
        while (iterator.hasNext()) {
            Projectile q = iterator.next();
            q.x += q.vx * dt;
            q.y += q.vy * dt;
            q.vy += 520f * dt;
            q.life -= dt;
            if (q.life <= 0f || q.x < cameraX - 800f || q.x > cameraX + getWidth() + 1000f) iterator.remove();
        }
    }

    private void collectGems() {
        for (Gem gem : gems) {
            if (!gem.collected && hit(x, y, BALL_R, gem.x, gem.y, gem.r)) {
                gem.collected = true;
                levelGems++;
                totalGems++;
                save();
                burst(gem.x, gem.y, 12, 0xFFFFD54A);
            }
        }
    }

    private void checkHazards() {
        for (RectF spike : spikes) if (circleRect(x, y, BALL_R, spike)) {
            respawn();
            return;
        }
        for (RectF spring : springs) if (circleRect(x, y, BALL_R, spring) && vy >= 0f) {
            y = spring.top - BALL_R;
            vy = -1340f;
            grounded = false;
            burst(x, y + BALL_R, 14, 0xFF74E7FF);
        }
        for (Projectile projectile : projectiles) if (hit(x, y, BALL_R, projectile.x, projectile.y, projectile.r)) {
            respawn();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        for (Enemy enemy : enemies) if (enemy.alive && hit(x, y, BALL_R, enemy.x, enemy.y, enemy.size * 0.72f)) {
            boolean stomp = vy > 0f && y + BALL_R < enemy.y + enemy.size * 0.32f && Math.abs(x - enemy.x) < enemy.size * 0.95f;
            if (stomp && now - enemy.lastHit > 220L) {
                enemy.alive = false;
                vy = -900f;
                burst(enemy.x, enemy.y, 20, 0xFFFFC04A);
            } else if (now - enemy.lastHit > 500L) {
                enemy.lastHit = now;
                respawn();
                return;
            }
        }
    }

    private void checkCheckpoints() {
        for (Checkpoint checkpoint : checkpoints) {
            if (!checkpoint.active && Math.abs(x - checkpoint.x) < 50f && Math.abs(y - checkpoint.y) < 120f) {
                checkpoint.active = true;
                checkpointX = checkpoint.x;
                checkpointY = checkpoint.y - 60f;
                burst(checkpoint.x, checkpoint.y - 55f, 16, 0xFF7DFFB2);
            }
        }
    }

    private void updateBoss(float dt) {
        if (!bossAlive) return;
        if (bossHp <= 0f) {
            bossAlive = false;
            burst(bossX, bossY, 55, 0xFFFFD166);
            return;
        }
        bossTimer += dt;
        bossX += (x - bossX) * Math.min(1f, dt * 1.15f);
        bossY = 590f + (float) Math.sin(bossTimer * 2.1f) * 36f;
        if (bossTimer > 1.15f) {
            bossTimer = 0f;
            float dx = x - bossX;
            float dy = y - bossY;
            float length = Math.max(1f, (float) Math.hypot(dx, dy));
            projectiles.add(new Projectile(bossX, bossY, dx / length * 430f, dy / length * 430f, 16f));
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (hit(x, y, BALL_R, bossX, bossY, 72f) && now - bossHitAt > 700L) respawn();
        if (vy > 0f && y + BALL_R < bossY + 22f && y + BALL_R > bossY - 50f && Math.abs(x - bossX) < 84f && now - bossHitAt > 450L) {
            bossHp -= 25f;
            bossHitAt = now;
            vy = -850f;
            burst(bossX, bossY, 22, 0xFFFFA95A);
        }
    }

    private void updateParticles(float dt) {
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle particle = iterator.next();
            particle.life -= dt;
            particle.x += particle.vx * dt;
            particle.y += particle.vy * dt;
            particle.vy += 520f * dt;
            particle.vx *= 0.985f;
            if (particle.life <= 0f) iterator.remove();
        }
    }

    private void followCamera(float dt) {
        float targetX = x - getWidth() * 0.34f;
        float targetY = y - getHeight() * 0.55f;
        cameraX += (targetX - cameraX) * Math.min(1f, dt * 5f);
        cameraY += (targetY - cameraY) * Math.min(1f, dt * 5f);
        float maxX = endlessMode ? Math.max(0f, segmentCursor - getWidth() * 0.75f) : Math.max(0f, worldW - getWidth());
        cameraX = clamp(cameraX, 0f, maxX);
        cameraY = clamp(cameraY, -80f, 430f);
        cameraShake *= 0.87f;
    }

    private void respawn() {
        deaths++;
        x = checkpointX;
        y = checkpointY;
        vx = 0f;
        vy = 0f;
        cameraShake = 0.55f;
        projectiles.clear();
        burst(x, y, 18, 0xFFFF6B6B);
    }

    private void finishLevel() {
        ui = 4;
        if (level < LEVELS) unlocked = Math.max(unlocked, level + 1);
        save();
        burst(x, y, 42, 0xFFFFD54A);
    }

    private void startLevel(int id) {
        level = clampInt(id, 1, LEVELS);
        endlessMode = false;
        elapsedMs = 0L;
        deaths = 0;
        levelGems = 0;
        endlessScore = 0;
        bossAlive = false;
        cameraX = 0f;
        cameraY = 0f;
        checkpointX = 260f;
        checkpointY = 650f;
        x = checkpointX;
        y = checkpointY;
        vx = 0f;
        vy = 0f;
        clearWorld();
        buildLevel(level);
        ui = 2;
    }

    private void startEndless() {
        level = ENDLESS;
        endlessMode = true;
        elapsedMs = 0L;
        deaths = 0;
        levelGems = 0;
        endlessScore = 0;
        bossAlive = false;
        cameraX = 0f;
        cameraY = 0f;
        checkpointX = 260f;
        checkpointY = 650f;
        x = checkpointX;
        y = checkpointY;
        vx = 0f;
        vy = 0f;
        endlessSeed = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        segmentCursor = 820f;
        clearWorld();
        solids.add(new RectF(0f, 805f, 820f, 950f));
        generateEndlessSegment();
        ui = 2;
    }

    private void buildLevel(int id) {
        int world = (id - 1) / 8;
        int variant = (id - 1) % 8;
        theme = world;
        worldW = 6000f + world * 520f + variant * 180f;
        Random r = new Random(id * 9973L);
        solids.add(new RectF(0f, 805f, 820f, 950f));
        float cursor = 735f;
        for (int i = 0; i < 18 + world; i++) {
            cursor += 55f + r.nextInt(95);
            float width = 360f + r.nextInt(290);
            float top = 775f - (i % 3) * 55f - (variant % 2) * 12f;
            solids.add(new RectF(cursor, top, cursor + width, top + 145f));
            if (i % 2 == 0) gems.add(new Gem(cursor + width * 0.46f, top - 72f, i * 41));
            if ((i + variant) % 3 == 1) spikes.add(new RectF(cursor + width * 0.66f, top - 24f, cursor + width * 0.66f + 50f, top + 18f));
            if ((i + world) % 5 == 2) springs.add(new RectF(cursor + width * 0.20f, top - 28f, cursor + width * 0.20f + 74f, top + 4f));
            if ((i + id) % 3 != 0) enemies.add(new Enemy(cursor + width * 0.76f, top - 33f, (i + id) % 3, 90f + (id % 5) * 14f));
            if (i % 4 == 2) platforms.add(new Platform(cursor + width * 0.33f, top - 150f, 160f, 25f, 50f + (i % 2) * 25f, 0.8f + (i % 3) * 0.2f, i % 2));
            if (i == 5 || i == 11) checkpoints.add(new Checkpoint(cursor + width * 0.18f, top));
            cursor += width;
        }
        while (cursor < worldW - 520f) {
            cursor += 85f;
            solids.add(new RectF(cursor, 820f, cursor + 480f, 960f));
            if (((int) cursor / 100) % 2 == 0) gems.add(new Gem(cursor + 215f, 742f, (int) cursor));
            cursor += 480f;
        }
        solids.add(new RectF(worldW - 600f, 560f, worldW - 270f, 620f));
        solids.add(new RectF(worldW - 270f, 690f, worldW + 30f, 950f));
        if (id % 8 == 0) {
            bossAlive = true;
            bossX = worldW - 650f;
            bossY = 600f;
            bossMaxHp = 125f + world * 40f;
            bossHp = bossMaxHp;
        }
    }

    private void generateEndlessSegment() {
        Random r = new Random(endlessSeed + (long) (segmentCursor * 31f));
        float gap = 65f + r.nextInt(95);
        float width = 390f + r.nextInt(330);
        float top = 720f - r.nextInt(180);
        float start = segmentCursor + gap;
        solids.add(new RectF(start, top, start + width, top + 145f));
        if (r.nextFloat() < 0.70f) gems.add(new Gem(start + width * 0.46f, top - 72f, r.nextInt()));
        if (r.nextFloat() < 0.45f) spikes.add(new RectF(start + width * 0.64f, top - 24f, start + width * 0.64f + 48f, top + 18f));
        if (r.nextFloat() < 0.22f) springs.add(new RectF(start + width * 0.20f, top - 28f, start + width * 0.20f + 72f, top + 4f));
        if (r.nextFloat() < 0.48f) enemies.add(new Enemy(start + width * 0.76f, top - 33f, r.nextInt(3), 95f + r.nextInt(70)));
        if (r.nextFloat() < 0.30f) platforms.add(new Platform(start + width * 0.34f, top - 155f, 160f, 25f, 45f + r.nextInt(45), 0.8f + r.nextFloat() * 0.8f, r.nextBoolean() ? 0 : 1));
        segmentCursor = start + width;
    }

    private void ensureEndlessWorld() {
        while (segmentCursor < x + getWidth() * 3f) generateEndlessSegment();
        float cut = cameraX - 1000f;
        trimSolids(cut);
        trimSpikes(cut);
        trimSprings(cut);
        trimPlatforms(cut);
        trimGems(cut);
        trimEnemies(cut);
    }

    private void trimSolids(float cut) { Iterator<RectF> it = solids.iterator(); while (it.hasNext()) if (it.next().right < cut) it.remove(); }
    private void trimSpikes(float cut) { Iterator<RectF> it = spikes.iterator(); while (it.hasNext()) if (it.next().right < cut) it.remove(); }
    private void trimSprings(float cut) { Iterator<RectF> it = springs.iterator(); while (it.hasNext()) if (it.next().right < cut) it.remove(); }
    private void trimPlatforms(float cut) { Iterator<Platform> it = platforms.iterator(); while (it.hasNext()) if (it.next().x < cut) it.remove(); }
    private void trimGems(float cut) { Iterator<Gem> it = gems.iterator(); while (it.hasNext()) { Gem g = it.next(); if (g.x < cut || g.collected) it.remove(); } }
    private void trimEnemies(float cut) { Iterator<Enemy> it = enemies.iterator(); while (it.hasNext()) if (it.next().x < cut) it.remove(); }

    private void clearWorld() {
        solids.clear();
        spikes.clear();
        springs.clear();
        checkpoints.clear();
        gems.clear();
        particles.clear();
        projectiles.clear();
        platforms.clear();
        enemies.clear();
    }

    private void render(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (ui == 0) menu(canvas, w, h);
        else if (ui == 1) levelSelect(canvas, w, h);
        else if (ui == 5) settings(canvas, w, h);
        else {
            world(canvas, w, h);
            if (ui == 3) pause(canvas, w, h);
            if (ui == 4) win(canvas, w, h);
        }
    }

    private void world(Canvas canvas, int w, int h) {
        int skyTop = theme == 0 ? 0xFF56CFFF : theme == 1 ? 0xFF4B78A1 : theme == 2 ? 0xFF33274E : 0xFF33454E;
        int skyBottom = theme == 0 ? 0xFFB8F18D : theme == 1 ? 0xFFD09A62 : theme == 2 ? 0xFF111A31 : 0xFF7A8178;
        p.setShader(new LinearGradient(0, 0, 0, h, skyTop, skyBottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        p.setShader(null);

        drawBackground(canvas, w, h);
        canvas.save();
        canvas.translate(-cameraX, -cameraY);
        if (cameraShake > 0.01f) canvas.translate((float) Math.sin(elapsedMs * 0.03) * cameraShake * 10f, (float) Math.cos(elapsedMs * 0.02) * cameraShake * 10f);

        for (RectF q : solids) drawGround(canvas, q);
        for (Platform platform : platforms) drawPlatform(canvas, platform);
        for (RectF q : springs) drawSpring(canvas, q);
        for (RectF q : spikes) drawSpike(canvas, q);
        for (Checkpoint checkpoint : checkpoints) drawCheckpoint(canvas, checkpoint);
        for (Gem gem : gems) if (!gem.collected) drawStar(canvas, gem);
        for (Enemy enemy : enemies) if (enemy.alive) enemy.draw(canvas);
        for (Projectile projectile : projectiles) drawProjectile(canvas, projectile);
        if (bossAlive) drawBoss(canvas);
        if (!endlessMode) drawGoal(canvas);
        for (Particle particle : particles) drawParticle(canvas, particle);
        drawBall(canvas);
        canvas.restore();

        drawHud(canvas, w, h);
        drawControls(canvas, w, h);
    }

    private void drawBackground(Canvas canvas, int w, int h) {
        if (theme == 0) {
            p.setColor(0x88FFFFFF);
            for (int i = 0; i < 5; i++) {
                float cx = (i * 270f + 40f) - (cameraX * 0.08f % 1350f);
                float cy = 90f + (i % 2) * 55f;
                canvas.drawOval(cx, cy, cx + 130f, cy + 38f, p);
                canvas.drawOval(cx + 45f, cy - 17f, cx + 175f, cy + 38f, p);
            }
            p.setColor(0xFFFFE17D);
            canvas.drawCircle(w - 90f, 88f, 40f, p);
        }
        drawHillLayer(canvas, w, h, 0.12f, theme == 2 ? 0xFF3B3151 : 0xFF82C96A, 125f);
        drawHillLayer(canvas, w, h, 0.23f, theme == 1 ? 0xFF8F6C4B : 0xFF5CAC50, 92f);
    }

    private void drawHillLayer(Canvas canvas, int w, int h, float factor, int color, float height) {
        p.setColor(color);
        Path path = new Path();
        float offset = -(cameraX * factor) % 520f;
        path.moveTo(0, h);
        for (int i = -520; i < w + 800; i += 170) {
            float yy = h - height - (float) Math.sin((i + cameraX * factor) * 0.008f) * 28f;
            path.lineTo(i + offset, yy);
        }
        path.lineTo(w, h);
        path.close();
        canvas.drawPath(path, p);
    }

    private void drawGround(Canvas canvas, RectF r) {
        int dirt = theme == 0 ? 0xFF71451E : theme == 1 ? 0xFF765539 : theme == 2 ? 0xFF353A45 : 0xFF56616A;
        int grass = theme == 0 ? 0xFF72C83F : theme == 1 ? 0xFF8AA65D : 0xFF64727B;
        p.setShadowLayer(8f, 0, 5, 0x55000000);
        p.setColor(dirt);
        canvas.drawRoundRect(r, 10f, 10f, p);
        p.clearShadowLayer();
        p.setColor(grass);
        canvas.drawRoundRect(r.left, r.top, r.right, Math.min(r.bottom, r.top + 20f), 8f, 8f, p);
        if (theme == 0) {
            p.setColor(0xFF4DAF2D);
            for (int i = (int) r.left; i < r.right; i += 34) {
                Path blade = new Path();
                blade.moveTo(i, r.top + 20f);
                blade.lineTo(i + 7f, r.top + 5f);
                blade.lineTo(i + 13f, r.top + 20f);
                blade.close();
                canvas.drawPath(blade, p);
            }
        }
        p.setColor(0x33502A13);
        for (int i = (int) r.left + 25; i < r.right; i += 92) canvas.drawOval(i, r.top + 45f, i + 34f, r.top + 63f, p);
        p.setColor(0x44331E16);
        for (int i = (int) r.left + 48; i < r.right; i += 145) canvas.drawCircle(i, r.top + 88f, 8f, p);
    }

    private void drawPlatform(Canvas canvas, Platform platform) {
        RectF r = platform.rect();
        p.setShadowLayer(8f, 0, 5, 0x66000000);
        p.setColor(0xFF8E673D);
        canvas.drawRoundRect(r, 9f, 9f, p);
        p.clearShadowLayer();
        p.setColor(0xFFB9D86A);
        canvas.drawRoundRect(r.left, r.top, r.right, r.top + 9f, 5f, 5f, p);
        p.setColor(0xFF5C4029);
        canvas.drawCircle(r.left + 20, r.centerY(), 4, p);
        canvas.drawCircle(r.right - 22, r.centerY(), 4, p);
    }

    private void drawSpring(Canvas canvas, RectF r) {
        p.setColor(0xFF334052);
        canvas.drawRoundRect(r, 8f, 8f, p);
        p.setColor(0xFF79E8FF);
        canvas.drawRoundRect(r.left + 6f, r.top + 4f, r.right - 6f, r.top + 10f, 4f, 4f, p);
    }

    private void drawSpike(Canvas canvas, RectF r) {
        p.setColor(0xFF8C929A);
        Path shadow = new Path();
        shadow.moveTo(r.left, r.bottom);
        shadow.lineTo(r.centerX(), r.top);
        shadow.lineTo(r.right, r.bottom);
        shadow.close();
        canvas.drawPath(shadow, p);
        p.setColor(0xFFE4E8EC);
        Path metal = new Path();
        metal.moveTo(r.left + 4f, r.bottom);
        metal.lineTo(r.centerX(), r.top + 5f);
        metal.lineTo(r.right - 4f, r.bottom);
        metal.close();
        canvas.drawPath(metal, p);
    }

    private void drawCheckpoint(Canvas canvas, Checkpoint q) {
        p.setColor(0xFFE2E7EA);
        canvas.drawRect(q.x, q.y - 72f, q.x + 5f, q.y, p);
        p.setColor(q.active ? 0xFF6BEB83 : 0xFFE84F59);
        Path flag = new Path();
        flag.moveTo(q.x + 5f, q.y - 70f);
        flag.lineTo(q.x + 54f, q.y - 54f);
        flag.lineTo(q.x + 5f, q.y - 38f);
        flag.close();
        canvas.drawPath(flag, p);
    }

    private void drawStar(Canvas canvas, Gem gem) {
        float pulse = 1f + (float) Math.sin((elapsedMs + gem.phase) * 0.006f) * 0.08f;
        canvas.save();
        canvas.translate(gem.x, gem.y);
        canvas.scale(pulse, pulse);
        p.setShadowLayer(16f, 0, 0, 0x99FFD34D);
        p.setColor(0xFFFFD34D);
        Path star = new Path();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float radius = i % 2 == 0 ? 18f : 8f;
            float sx = (float) Math.cos(a) * radius;
            float sy = (float) Math.sin(a) * radius;
            if (i == 0) star.moveTo(sx, sy); else star.lineTo(sx, sy);
        }
        star.close();
        canvas.drawPath(star, p);
        p.clearShadowLayer();
        p.setColor(0xFFFFF4B5);
        canvas.drawCircle(-5f, -7f, 3f, p);
        canvas.restore();
    }

    private void drawGoal(Canvas canvas) {
        float gx = worldW - 110f;
        float gy = 625f;
        p.setColor(0xFFE3E7E8);
        canvas.drawRect(gx, gy - 80f, gx + 5f, gy, p);
        p.setColor(0xFF63D86F);
        Path flag = new Path();
        flag.moveTo(gx + 5f, gy - 78f);
        flag.lineTo(gx + 55f, gy - 61f);
        flag.lineTo(gx + 5f, gy - 44f);
        flag.close();
        canvas.drawPath(flag, p);
        p.setColor(0xFF63D86F);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(gx - 4f, gy + 2f, 8f, p);
    }

    private void drawProjectile(Canvas canvas, Projectile q) {
        p.setShadowLayer(14f, 0, 0, 0xAAFF613F);
        p.setColor(0xFFFF7043);
        canvas.drawCircle(q.x, q.y, q.r, p);
        p.clearShadowLayer();
    }

    private void drawBoss(Canvas canvas) {
        float size = 72f;
        p.setShadowLayer(22f, 0, 8, 0x99000000);
        p.setColor(0xFF4A4E58);
        canvas.drawRoundRect(bossX - size, bossY - size, bossX + size, bossY + size, 18f, 18f, p);
        p.clearShadowLayer();
        p.setColor(0xFF222630);
        canvas.drawRoundRect(bossX - 52f, bossY - 42f, bossX + 52f, bossY + 28f, 12f, 12f, p);
        p.setColor(0xFFFF4E52);
        canvas.drawCircle(bossX - 25f, bossY - 13f, 10f, p);
        canvas.drawCircle(bossX + 25f, bossY - 13f, 10f, p);
        p.setColor(Color.WHITE);
        canvas.drawCircle(bossX - 21f, bossY - 17f, 4f, p);
        canvas.drawCircle(bossX + 29f, bossY - 17f, 4f, p);
        p.setColor(0xFF191C23);
        canvas.drawRoundRect(bossX - 34f, bossY + 7f, bossX + 34f, bossY + 20f, 6f, 6f, p);
        p.setColor(0xFF191C23);
        canvas.drawRoundRect(bossX - 82f, bossY - 95f, bossX + 82f, bossY - 84f, 5f, 5f, p);
        p.setColor(0xFFFFD35A);
        float ratio = bossMaxHp <= 0 ? 0 : clamp(bossHp / bossMaxHp, 0f, 1f);
        canvas.drawRoundRect(bossX - 80f, bossY - 93f, bossX - 80f + 160f * ratio, bossY - 86f, 4f, 4f, p);
    }

    private void drawParticle(Canvas canvas, Particle particle) {
        p.setAlpha((int) (255f * clamp(particle.life / particle.maxLife, 0f, 1f)));
        p.setColor(particle.color);
        canvas.drawCircle(particle.x, particle.y, particle.size, p);
        p.setAlpha(255);
    }

    private void drawBall(Canvas canvas) {
        int color = skin == 1 ? 0xFFFFB52F : skin == 2 ? 0xFF3BBF7D : 0xFFE9434A;
        canvas.save();
        canvas.translate(x, y);
        p.setShadowLayer(18f, 0, 10f, 0x66000000);
        p.setShader(new RadialGradient(-10f, -13f, BALL_R + 5f, Color.WHITE, color, Shader.TileMode.CLAMP));
        canvas.drawCircle(0, 0, BALL_R, p);
        p.setShader(null);
        p.clearShadowLayer();

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5f);
        p.setColor(0x88FFFFFF);
        float rotation = vx * 10f;
        canvas.drawArc(-24f, -24f, 24f, 24f, rotation, 135f, false, p);
        p.setStyle(Paint.Style.FILL);

        p.setColor(Color.WHITE);
        canvas.drawCircle(-10f, -8f, 7f, p);
        canvas.drawCircle(10f, -8f, 7f, p);
        p.setColor(0xFF202532);
        canvas.drawCircle(-8f, -7f, 3f, p);
        canvas.drawCircle(12f, -7f, 3f, p);
        p.setColor(0x88202532);
        canvas.drawOval(-9f, 6f, 9f, 18f, p);
        canvas.restore();
    }

    private void drawHud(Canvas canvas, int w, int h) {
        p.setColor(0xCC182432);
        canvas.drawRoundRect(18f, 16f, 340f, 70f, 18f, 18f, p);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(20f);
        String title = endlessMode ? "ENDLESS" : "LEVEL " + level;
        canvas.drawText(title, 36f, 49f, p);
        p.setTextSize(16f);
        canvas.drawText("★ " + levelGems, endlessMode ? 175f : 145f, 49f, p);
        if (endlessMode) {
            p.setColor(0xFFFFD54A);
            canvas.drawText("BEST " + bestEndless, 235f, 49f, p);
        } else {
            p.setColor(0xFFBDEBFF);
            canvas.drawText(formatTime(elapsedMs), 235f, 49f, p);
        }
        p.setColor(0xAA182432);
        canvas.drawRoundRect(w - 88f, 16f, w - 18f, 70f, 18f, 18f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(25f);
        p.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Ⅱ", w - 53f, 51f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawControls(Canvas canvas, int w, int h) {
        float cy = h - 92f;
        drawControlCircle(canvas, 84f, cy, 66f, leftPointer >= 0, "‹");
        drawControlCircle(canvas, 220f, cy, 66f, rightPointer >= 0, "›");
        drawControlCircle(canvas, w - 92f, cy, 72f, jumpPointer >= 0, "↑");
    }

    private void drawControlCircle(Canvas canvas, float cx, float cy, float radius, boolean pressed, String text) {
        p.setColor(pressed ? 0xAAFFF4D1 : 0x553F5567);
        canvas.drawCircle(cx, cy, radius, p);
        stroke.setColor(0x99FFFFFF);
        stroke.setStrokeWidth(3f);
        canvas.drawCircle(cx, cy, radius, stroke);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(radius * 0.72f);
        p.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, cx, cy + radius * 0.25f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void menu(Canvas canvas, int w, int h) {
        p.setShader(new LinearGradient(0, 0, w, h, 0xFF52C9FF, 0xFF8CDB63, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        p.setShader(null);
        drawHillLayer(canvas, w, h, 0.04f, 0x5574B955, 180f);
        float cx = w * 0.5f;
        drawLogoBall(canvas, cx, h * 0.24f, 78f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setColor(Color.WHITE);
        p.setTextSize(44f);
        canvas.drawText("BALL", cx, h * 0.46f, p);
        p.setColor(0xFFFFE36F);
        p.setTextSize(38f);
        canvas.drawText("PLATFORMER", cx, h * 0.53f, p);
        button(canvas, cx, h * 0.66f, 280f, 64f, "PLAY", 0xCCDF3D4C);
        button(canvas, cx, h * 0.77f, 280f, 58f, "LEVEL SELECT", 0x883D566B);
        button(canvas, cx, h * 0.87f, 280f, 58f, "ENDLESS MODE", 0x886B7A47);
        button(canvas, cx + 175f, h * 0.77f, 88f, 58f, "⚙", 0x883D566B);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(13f);
        p.setColor(0xCCFFFFFF);
        canvas.drawText("32 levels • square minions • bosses • endless", cx, h - 18f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawLogoBall(Canvas canvas, float cx, float cy, float radius) {
        p.setShader(new RadialGradient(cx - 22f, cy - 25f, radius, Color.WHITE, 0xFFD92836, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        canvas.drawCircle(cx - 24f, cy - 10f, 12f, p);
        canvas.drawCircle(cx + 24f, cy - 10f, 12f, p);
        p.setColor(0xFF202532);
        canvas.drawCircle(cx - 21f, cy - 9f, 5f, p);
        canvas.drawCircle(cx + 27f, cy - 9f, 5f, p);
    }

    private void levelSelect(Canvas canvas, int w, int h) {
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF253C59, 0xFF101923, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(34f);
        canvas.drawText("LEVEL SELECT", 28f, 48f, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(15f);
        p.setColor(0xBFFFFFFF);
        canvas.drawText("Unlocked " + unlocked + " / " + LEVELS, 30f, 74f, p);
        int columns = 8;
        float cellW = (w - 70f) / columns;
        for (int i = 1; i <= LEVELS; i++) {
            int row = (i - 1) / columns;
            int col = (i - 1) % columns;
            float cx = 40f + col * cellW + cellW * 0.5f;
            float cy = 122f + row * 72f;
            boolean open = i <= unlocked;
            p.setColor(open ? 0xFFDF3D4C : 0xFF26323E);
            canvas.drawRoundRect(cx - 36f, cy - 24f, cx + 36f, cy + 24f, 14f, 14f, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.WHITE);
            p.setTextSize(19f);
            canvas.drawText(String.valueOf(i), cx, cy + 7f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }
        button(canvas, w - 74f, h - 42f, 118f, 54f, "BACK", 0x88617484);
    }

    private void pause(Canvas canvas, int w, int h) {
        overlay(canvas);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(44f);
        canvas.drawText("PAUSED", w * 0.5f, h * 0.27f, p);
        button(canvas, w * 0.5f, h * 0.48f, 240f, 60f, "RESUME", 0xCCDF3D4C);
        button(canvas, w * 0.5f, h * 0.61f, 240f, 58f, "RESTART", 0x88617484);
        button(canvas, w * 0.5f, h * 0.74f, 240f, 58f, "LEVEL SELECT", 0x88617484);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void win(Canvas canvas, int w, int h) {
        overlay(canvas);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setColor(0xFFFFD54A);
        p.setTextSize(40f);
        canvas.drawText(endlessMode ? "ENDLESS RUN" : (level == LEVELS ? "WORLD COMPLETE!" : "LEVEL COMPLETE!"), w * 0.5f, h * 0.25f, p);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(20f);
        if (endlessMode) canvas.drawText("Score " + endlessScore + "   Best " + bestEndless, w * 0.5f, h * 0.38f, p);
        else canvas.drawText("Time " + formatTime(elapsedMs) + "   Gems " + levelGems, w * 0.5f, h * 0.38f, p);
        button(canvas, w * 0.5f, h * 0.56f, 260f, 60f, endlessMode ? "RUN AGAIN" : (level < LEVELS ? "NEXT LEVEL" : "PLAY AGAIN"), 0xCCDF3D4C);
        button(canvas, w * 0.5f, h * 0.69f, 260f, 56f, "LEVEL SELECT", 0x88617484);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void settings(Canvas canvas, int w, int h) {
        p.setColor(0xFF101923);
        canvas.drawRect(0, 0, w, h, p);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(38f);
        canvas.drawText("SETTINGS", 30f, 60f, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(20f);
        canvas.drawText("Sound", 38f, 140f, p);
        button(canvas, 220f, 130f, 150f, 54f, muted ? "OFF" : "ON", 0x883D566B);
        canvas.drawText("Ball skin", 38f, 220f, p);
        button(canvas, 220f, 210f, 150f, 54f, skin == 0 ? "RED" : skin == 1 ? "GOLD" : "GREEN", 0x883D566B);
        canvas.drawText("Lifetime stars: " + totalGems, 38f, 292f, p);
        canvas.drawText("Best endless score: " + bestEndless, 38f, 326f, p);
        button(canvas, 220f, 414f, 220f, 56f, "BACK", 0x88617484);
    }

    private void overlay(Canvas canvas) {
        p.setColor(0xAA05080D);
        canvas.drawRect(0, 0, getWidth(), getHeight(), p);
    }

    private void button(Canvas canvas, float cx, float cy, float w, float h, String text, int color) {
        p.setColor(color);
        canvas.drawRoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, 18f, 18f, p);
        stroke.setColor(0x77FFFFFF);
        stroke.setStrokeWidth(2f);
        canvas.drawRoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, 18f, 18f, stroke);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(h * 0.37f);
        canvas.drawText(text, cx, cy + h * 0.13f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int actionIndex = event.getActionIndex();
        final float tx = event.getX(actionIndex);
        final float ty = event.getY(actionIndex);
        final int pointerId = event.getPointerId(actionIndex);

        if (ui == 0 && action == MotionEvent.ACTION_UP) {
            float cx = getWidth() * 0.5f;
            float h = getHeight();
            if (hitRect(tx, ty, cx, h * 0.66f, 300f, 76f)) { startLevel(unlocked); return true; }
            if (hitRect(tx, ty, cx, h * 0.77f, 300f, 70f)) { ui = 1; return true; }
            if (hitRect(tx, ty, cx, h * 0.87f, 300f, 70f)) { startEndless(); return true; }
            if (hitRect(tx, ty, cx + 175f, h * 0.77f, 100f, 72f)) { ui = 5; return true; }
            return true;
        }

        if (ui == 1 && action == MotionEvent.ACTION_UP) {
            float cellW = (getWidth() - 70f) / 8f;
            for (int i = 1; i <= LEVELS; i++) {
                int row = (i - 1) / 8;
                int col = (i - 1) % 8;
                float cx = 40f + col * cellW + cellW * 0.5f;
                float cy = 122f + row * 72f;
                if (i <= unlocked && hitRect(tx, ty, cx, cy, 82f, 60f)) { startLevel(i); return true; }
            }
            if (hitRect(tx, ty, getWidth() - 74f, getHeight() - 42f, 130f, 70f)) ui = 0;
            return true;
        }

        if (ui == 5 && action == MotionEvent.ACTION_UP) {
            if (hitRect(tx, ty, 220f, 130f, 180f, 70f)) { muted = !muted; save(); return true; }
            if (hitRect(tx, ty, 220f, 210f, 180f, 70f)) { skin = (skin + 1) % 3; save(); return true; }
            if (hitRect(tx, ty, 220f, 414f, 250f, 72f)) { ui = 0; return true; }
            return true;
        }

        if (ui == 3 && action == MotionEvent.ACTION_UP) {
            if (hitRect(tx, ty, getWidth() * 0.5f, getHeight() * 0.48f, 270f, 75f)) { ui = 2; clearPointers(); return true; }
            if (hitRect(tx, ty, getWidth() * 0.5f, getHeight() * 0.61f, 270f, 70f)) { if (endlessMode) startEndless(); else startLevel(level); return true; }
            if (hitRect(tx, ty, getWidth() * 0.5f, getHeight() * 0.74f, 270f, 70f)) { ui = 1; clearPointers(); return true; }
            return true;
        }

        if (ui == 4 && action == MotionEvent.ACTION_UP) {
            if (hitRect(tx, ty, getWidth() * 0.5f, getHeight() * 0.56f, 290f, 75f)) { if (endlessMode) startEndless(); else startLevel(level < LEVELS ? level + 1 : 1); return true; }
            if (hitRect(tx, ty, getWidth() * 0.5f, getHeight() * 0.69f, 280f, 70f)) { ui = 1; return true; }
            return true;
        }

        if (ui == 2) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (ty < 100f && tx > getWidth() - 145f) { ui = 3; clearPointers(); return true; }
                assignPointer(pointerId, tx, ty);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                refreshPointerRoles(event);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                clearPointer(pointerId);
                refreshPointerRoles(event);
                return true;
            }
        }
        return true;
    }

    private void assignPointer(int id, float tx, float ty) {
        float h = getHeight();
        if (ty < h - 175f) return;
        if (tx < 150f && leftPointer < 0) leftPointer = id;
        else if (tx < 300f && rightPointer < 0) rightPointer = id;
        else if (tx > getWidth() - 180f && jumpPointer < 0) {
            jumpPointer = id;
            jumpQueued = true;
        }
    }

    private void refreshPointerRoles(MotionEvent event) {
        int left = leftPointer;
        int right = rightPointer;
        int jump = jumpPointer;
        for (int i = 0; i < event.getPointerCount(); i++) {
            int id = event.getPointerId(i);
            float tx = event.getX(i);
            float ty = event.getY(i);
            if (id == left || id == right || id == jump) continue;
            assignPointer(id, tx, ty);
        }
    }

    private void clearPointer(int id) {
        if (leftPointer == id) leftPointer = -1;
        if (rightPointer == id) rightPointer = -1;
        if (jumpPointer == id) jumpPointer = -1;
    }

    private void clearPointers() {
        leftPointer = -1;
        rightPointer = -1;
        jumpPointer = -1;
    }

    private boolean hitRect(float x, float y, float cx, float cy, float w, float h) {
        return Math.abs(x - cx) <= w / 2f && Math.abs(y - cy) <= h / 2f;
    }

    private static boolean circleRect(float cx, float cy, float radius, RectF r) {
        float nx = clamp(cx, r.left, r.right);
        float ny = clamp(cy, r.top, r.bottom);
        float dx = cx - nx;
        float dy = cy - ny;
        return dx * dx + dy * dy <= radius * radius;
    }

    private static boolean hit(float ax, float ay, float ar, float bx, float by, float br) {
        float dx = ax - bx;
        float dy = ay - by;
        float rr = ar + br;
        return dx * dx + dy * dy <= rr * rr;
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static int clampInt(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String formatTime(long ms) { return String.format(Locale.US, "%02d:%02d", (ms / 1000L) / 60L, (ms / 1000L) % 60L); }

    private void burst(float px, float py, int count, int color) {
        for (int i = 0; i < count; i++) {
            double angle = particlesRandom.nextDouble() * Math.PI * 2.0;
            float speed = 50f + particlesRandom.nextFloat() * 290f;
            particles.add(new Particle(px, py, (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed - 50f, 2f + particlesRandom.nextFloat() * 5f, color, 0.35f + particlesRandom.nextFloat() * 0.55f));
        }
    }

    private void save() {
        prefs.edit().putInt("unlocked", unlocked).putInt("gems", totalGems).putInt("best_endless", bestEndless).putInt("skin", skin).putBoolean("muted", muted).apply();
    }

    private static final class Gem {
        final float x, y, r = 17f;
        final int phase;
        boolean collected;
        Gem(float x, float y, int phase) { this.x = x; this.y = y; this.phase = phase; }
    }

    private static final class Checkpoint {
        final float x, y;
        boolean active;
        Checkpoint(float x, float y) { this.x = x; this.y = y; }
    }

    private static final class Particle {
        float x, y, vx, vy, size, life, maxLife;
        final int color;
        Particle(float x, float y, float vx, float vy, float size, int color, float life) { this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.size = size; this.color = color; this.life = life; this.maxLife = life; }
    }

    private static final class Projectile {
        float x, y, vx, vy, r, life = 5f;
        Projectile(float x, float y, float vx, float vy, float r) { this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.r = r; }
    }

    private static final class Platform {
        final float baseX, baseY, w, h, range, speed;
        float x, y, t, dx, dy;
        final int axis;
        Platform(float x, float y, float w, float h, float range, float speed, int axis) { this.baseX = this.x = x; this.baseY = this.y = y; this.w = w; this.h = h; this.range = range; this.speed = speed; this.axis = axis; }
        RectF rect() { return new RectF(x, y, x + w, y + h); }
    }

    private final class Enemy {
        float x, y, baseY, speed, t, lastHit;
        final float minX, maxX, size = 28f;
        final int type;
        int dir = 1;
        boolean alive = true;

        Enemy(float x, float y, int type, float speed) {
            this.x = x;
            this.y = y;
            this.baseY = y;
            this.type = type;
            this.speed = speed;
            this.minX = x - 120f;
            this.maxX = x + 120f;
        }

        void update(float dt) {
            t += dt;
            if (type == 0) {
                x += dir * speed * dt;
                if (x < minX || x > maxX) dir *= -1;
            } else if (type == 1) {
                x += (float) Math.sin(t * 2.5f) * speed * 0.45f * dt;
                y = baseY + (float) Math.sin(t * 3.1f) * 40f;
            } else {
                x += dir * speed * dt;
                y = baseY - (float) Math.abs(Math.sin(t * 2.9f)) * 38f;
                if (x < minX || x > maxX) dir *= -1;
            }
        }

        void draw(Canvas canvas) {
            int body = type == 0 ? 0xFF4D5664 : type == 1 ? 0xFF694A68 : 0xFF6A4F2D;
            int accent = type == 0 ? 0xFFFF5B60 : type == 1 ? 0xFFD77CC6 : 0xFFFFAA3B;
            float s = size;
            p.setShadowLayer(10f, 0, 6f, 0x66000000);
            p.setColor(body);
            canvas.drawRoundRect(x - s, y - s, x + s, y + s, 7f, 7f, p);
            p.clearShadowLayer();
            p.setColor(accent);
            canvas.drawRect(x - s + 5f, y - s + 5f, x + s - 5f, y - s + 10f, p);
            p.setColor(Color.WHITE);
            canvas.drawCircle(x - 8f, y - 5f, 5f, p);
            canvas.drawCircle(x + 8f, y - 5f, 5f, p);
            p.setColor(0xFF22252C);
            canvas.drawCircle(x - 7f + dir * 1.5f, y - 4f, 2.5f, p);
            canvas.drawCircle(x + 9f + dir * 1.5f, y - 4f, 2.5f, p);
            p.setColor(0xFF23252B);
            canvas.drawRoundRect(x - 10f, y + 7f, x + 10f, y + 13f, 3f, 3f, p);
        }
    }
}
