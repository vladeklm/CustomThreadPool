import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class CustomThreadFactory implements ThreadFactory {
  private final String namePrefix;
  private final AtomicInteger threadNumber = new AtomicInteger(1);

  public CustomThreadFactory(String poolName) {
    this.namePrefix = poolName + "-worker-";
  }

  @Override
  public Thread newThread(Runnable r) {
    Thread thread = new Thread(r, namePrefix + threadNumber.getAndIncrement());
    System.out.println("[ThreadFactory] Creating new thread: " + thread.getName());

    // Логирование завершения потока
    thread.setUncaughtExceptionHandler((t, e) -> {
      System.out.println("[Worker] " + t.getName() + " terminated with exception: " + e.getMessage());
    });

    return thread;
  }
}
