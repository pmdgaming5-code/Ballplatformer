package com.pmdgaming5.ballplatformer;

import android.os.SystemClock;
import android.view.View;

import java.lang.reflect.Field;

/**
 * Fine-tunes the already-working GameView physics without touching rendering,
 * level generation, enemy logic or the existing touch UI.
 *
 * The goal is a responsive "jump'n'roll" feel: strong ground acceleration,
 * smooth rolling friction, lighter air steering and a softer jump cut.
 */
public final class PhysicsTuner implements Runnable {
    private final GameView view;

    private final Field ui;
    private final Field leftPointer;
    private final Field rightPointer;
    private final Field jumpPointer;
    private final Field grounded;
    private final Field vx;
    private final Field vy;

    private long lastNs;
    private boolean posted;

    private PhysicsTuner(GameView view) throws ReflectiveOperationException {
        this.view = view;
        Class<?> c = GameView.class;
        ui = field(c, "ui");
        leftPointer = field(c, "leftPointer");
        rightPointer = field(c, "rightPointer");
        jumpPointer = field(c, "jumpPointer");
        grounded = field(c, "grounded");
        vx = field(c, "vx");
        vy = field(c, "vy");
        lastNs = System.nanoTime();
    }

    public static void attach(GameView view) {
        try {
            PhysicsTuner tuner = new PhysicsTuner(view);
            tuner.start();
        } catch (ReflectiveOperationException ignored) {
            // Never let the optional tuning layer break the game.
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private void start() {
        if (posted) return;
        posted = true;
        view.postOnAnimation(this);
    }

    @Override
    public void run() {
        long now = System.nanoTime();
        float dt = Math.min(0.033f, Math.max(0.001f, (now - lastNs) / 1_000_000_000f));
        lastNs = now;

        try {
            if (ui.getInt(view) == 2) tune(dt);
        } catch (IllegalAccessException ignored) {
            // Keep the game alive even if reflection is unavailable.
        }

        view.postOnAnimation(this);
    }

    private void tune(float dt) throws IllegalAccessException {
        boolean left = leftPointer.getInt(view) >= 0;
        boolean right = rightPointer.getInt(view) >= 0;
        boolean jumpHeld = jumpPointer.getInt(view) >= 0;
        boolean onGround = grounded.getBoolean(view);

        float speed = vx.getFloat(view);
        float vertical = vy.getFloat(view);

        // Red Ball-style movement is intentionally smooth rather than snappy:
        // you build rolling speed and then coast when the button is released.
        final float groundMax = 620f;
        final float airMax = 570f;
        final float groundAccel = 2500f;
        final float airAccel = 1500f;

        float target = 0f;
        if (left ^ right) {
            target = left ? -groundMax : groundMax;
        }

        if (left || right) {
            float max = onGround ? groundMax : airMax;
            target = left ? -max : max;
            float accel = onGround ? groundAccel : airAccel;
            speed = moveTowards(speed, target, accel * dt);
        } else {
            // Preserve momentum while rolling, but gradually settle to rest.
            float drag = onGround ? 5.4f : 1.25f;
            speed *= (float) Math.exp(-drag * dt);
            if (Math.abs(speed) < 3f) speed = 0f;
        }

        // Avoid the old update loop pushing the ball above the intended speed.
        float cap = onGround ? groundMax : airMax;
        speed = clamp(speed, -cap, cap);

        // Earlier jump release = stronger gravity during the ascent, producing
        // a controllable short-hop while a held jump reaches full height.
        if (!jumpHeld && vertical < -250f) {
            vertical += 1250f * dt;
        }

        // Keep the landing feeling clean and avoid tiny downward jitter.
        if (onGround && vertical > -40f && vertical < 40f) {
            vertical = 0f;
        }

        vx.setFloat(view, speed);
        vy.setFloat(view, vertical);
    }

    private static float moveTowards(float current, float target, float maxDelta) {
        if (current < target) return Math.min(current + maxDelta, target);
        if (current > target) return Math.max(current - maxDelta, target);
        return target;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
