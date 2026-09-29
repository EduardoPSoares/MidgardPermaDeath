package me.ray.rpermadeath.replay;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Armazena uma gravação completa de replay
 */
public class ReplayRecording {
    private final UUID deathPlayerId;
    private final Location deathLocation;
    private final long deathTime;
    private final List<ReplayFrame> frames;
    private boolean finalized;
    private State state;
    private long postDeathStartTime;

    public enum State {
        DOWNED,
        POST_DEATH,
        FINALIZED
    }
    
    public ReplayRecording(UUID deathPlayerId, Location deathLocation) {
        this.deathPlayerId = deathPlayerId;
        this.deathLocation = deathLocation;
        this.deathTime = System.currentTimeMillis();
        this.frames = new ArrayList<>();
        this.finalized = false;
        this.state = State.POST_DEATH; // Default to POST_DEATH for backward compatibility or instant death
        this.postDeathStartTime = this.deathTime;
    }

    public void setState(State state) {
        this.state = state;
    }

    public State getState() {
        return state;
    }

    public void setPostDeathStartTime(long postDeathStartTime) {
        this.postDeathStartTime = postDeathStartTime;
    }

    public long getPostDeathStartTime() {
        return postDeathStartTime;
    }
    
    public void addFrame(ReplayFrame frame) {
        if (!finalized) {
            frames.add(frame);
        }
    }
    
    public void finalizeRecording() {
        this.finalized = true;
    }
    
    public boolean isFinalized() {
        return finalized;
    }
    
    public UUID getDeathPlayerId() {
        return deathPlayerId;
    }
    
    public Location getDeathLocation() {
        return deathLocation;
    }
    
    public long getDeathTime() {
        return deathTime;
    }
    
    public List<ReplayFrame> getFrames() {
        return new ArrayList<>(frames);
    }
    
    public ReplayFrame getFrame(int index) {
        if (index < 0 || index >= frames.size()) return null;
        return frames.get(index);
    }
    
    public int getFrameCount() {
        return frames.size();
    }
    
    public long getDurationMillis() {
        if (frames.isEmpty()) return 0;
        return frames.get(frames.size() - 1).getTimestamp() - frames.get(0).getTimestamp();
    }
    
    public long getDurationSeconds() {
        return getDurationMillis() / 1000;
    }
}
