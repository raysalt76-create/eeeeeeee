package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;

public class TriggerBotCooldownEvent implements Event {
    private double threshold;
    private final net.minecraft.entity.Entity target;

    public TriggerBotCooldownEvent(double defaultThreshold, net.minecraft.entity.Entity target) {
        this.threshold = defaultThreshold;
        this.target = target;
    }

    public net.minecraft.entity.Entity getTarget() {
        return target;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }
}
