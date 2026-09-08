package me.redot.grin.agent;

import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;

import java.util.Objects;

final class GrinSignalListener implements SignalListener {

    private final Runnable action;

    GrinSignalListener(Runnable action) {
        this.action = Objects.requireNonNull(action);
    }

    @Override
    public void signal(Channel channel, Signal signal) {
        action.run();
    }
}