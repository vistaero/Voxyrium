package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.TrackedObject;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class QueuedServiceSlice<T> extends ServiceSlice {
    private final ConcurrentLinkedDeque<T> queue = new ConcurrentLinkedDeque<>();

    QueuedServiceSlice(ServiceThreadPool threadPool, Supplier<Pair<Consumer<T>, Runnable>> workerGenerator, String name, int weightPerJob, BooleanSupplier condition) {
        super(threadPool, null, name, weightPerJob, condition);
        //Fuck off java with the this bullshit before super constructor, fucking bullshit
        super.setWorkerGenerator(() -> {
            var work = workerGenerator.get();
            var consumer = work.left();
            return new Pair<>(() -> consumer.accept(this.queue.pop()), work.right());
        });
    }

    @Override
    public void execute() {
        throw new IllegalStateException("Cannot call .execute() on a QueuedServiceSlice");
    }

    public void enqueue(T obj) {
        this.queue.add(obj);
        super.execute();
    }
}
