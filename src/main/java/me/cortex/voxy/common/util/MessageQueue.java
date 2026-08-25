package me.cortex.voxy.common.util;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

public class MessageQueue <T> {
    private final Consumer<T> consumer;
    private final ConcurrentLinkedDeque<T> queue = new ConcurrentLinkedDeque<>();

    public MessageQueue(Consumer<T> consumer) {
        this.consumer = consumer;
    }

    public void push(T obj) {
        this.queue.add(obj);
    }

    public int consume() {
        return this.consume(Integer.MAX_VALUE);
    }

    public int consume(int max) {
        int i = 0;
        while (i < max) {
            var entry = this.queue.poll();
            if (entry == null) return i;
            i++;
            this.consumer.accept(entry);
        }
        return i;
    }

    public final void clear(Consumer<T> cleaner) {
        while (!this.queue.isEmpty()) {
            cleaner.accept(this.queue.pop());
        }
    }

}
