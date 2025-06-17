
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadFactory;

public class Worker implements Runnable {
  private final Thread thread;
  private final BlockingQueue<Runnable> taskQueue;
  private final CustomThreadPool pool;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final int id;

  public Worker(int id, BlockingQueue<Runnable> taskQueue, CustomThreadPool pool, ThreadFactory threadFactory) {
    this.id = id;
    this.taskQueue = taskQueue;
    this.pool = pool;
    this.thread = threadFactory.newThread(this);
  }

  public void start() {
    thread.start();
  }

  public void interrupt() {
    running.set(false);
    thread.interrupt();
  }

  @Override
  public void run() {
    try {
      while (running.get() && !Thread.currentThread().isInterrupted()) {
        Runnable task = null;

        // Ожидаем задачу из очереди с таймаутом
        try {
          task = taskQueue.poll(pool.getKeepAliveTime(), pool.getTimeUnit());
        } catch (InterruptedException e) {
          // Прерывание - сигнал для остановки
          running.set(false);
          Thread.currentThread().interrupt(); // Сохраняем флаг прерывания
          break;
        }

        if (task != null) {
          try {
            // Проверяем, что пул не завершается
            if (!pool.isShutdown()) {
              System.out.println("[Worker] " + Thread.currentThread().getName() + " executes " + task);
              task.run();
            } else {
              // Пул завершается, возвращаем задачу в очередь
              taskQueue.offer(task);
              running.set(false);
              break;
            }
          } catch (Throwable t) {
            System.out.println("[Worker] Error executing task: " + t.getMessage());
          } finally {
            // Проверяем, не был ли поток прерван во время выполнения задачи
            if (Thread.currentThread().isInterrupted()) {
              running.set(false);
              break;
            }
          }
        } else if (pool.allowThreadTimeout() && pool.getWorkerCount() > pool.getCorePoolSize()) {
          // Таймаут бездействия - завершаем поток, если их больше, чем corePoolSize
          System.out.println("[Worker] " + Thread.currentThread().getName() + " idle timeout, stopping.");
          running.set(false);
          pool.removeWorker(this);
          break;
        }

        // Проверяем состояние пула после каждой итерации
        if (pool.isShutdown() && taskQueue.isEmpty()) {
          running.set(false);
          break;
        }
      }
    } finally {
      // Логируем завершение потока
      System.out.println("[Worker] " + Thread.currentThread().getName() + " terminated.");
      pool.removeWorker(this); // Гарантируем удаление воркера из пула при завершении
    }
  }

  public boolean isRunning() {
    return running.get();
  }

  public Thread getThread() {
    return thread;
  }

  public int getId() {
    return id;
  }

  public BlockingQueue<Runnable> getTaskQueue() {
    return taskQueue;
  }
}
