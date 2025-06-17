
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class CustomThreadPool implements CustomExecutor {
  private final int corePoolSize;
  private final int maxPoolSize;
  private final int minSpareThreads;
  private final long keepAliveTime;
  private final TimeUnit timeUnit;
  private final int queueSize;
  private final ThreadFactory threadFactory;
  private final RejectedTaskHandler rejectedHandler;

  // Map для хранения рабочих потоков и их очередей
  private final Map<Integer, Worker> workers = new ConcurrentHashMap<>();
  private final List<BlockingQueue<Runnable>> taskQueues = new ArrayList<>();

  // Счетчик для генерации ID рабочих потоков
  private final AtomicInteger workerIdGenerator = new AtomicInteger(0);
  // Счетчик для распределения задач методом Round Robin
  private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

  // Блокировка для синхронизации изменений пула
  private final ReentrantLock mainLock = new ReentrantLock();
  // Флаг состояния пула (активен или завершается)
  private final AtomicBoolean isShutdown = new AtomicBoolean(false);

  // Счетчик ожидающих выполнения задач
  private final AtomicInteger pendingTasks = new AtomicInteger(0);

  public CustomThreadPool(int corePoolSize, int maxPoolSize, int minSpareThreads,
                          long keepAliveTime, TimeUnit timeUnit, int queueSize,
                          ThreadFactory threadFactory, RejectedTaskHandler rejectedHandler) {
    this.corePoolSize = corePoolSize;
    this.maxPoolSize = maxPoolSize;
    this.minSpareThreads = minSpareThreads;
    this.keepAliveTime = keepAliveTime;
    this.timeUnit = timeUnit;
    this.queueSize = queueSize;
    this.threadFactory = threadFactory;
    this.rejectedHandler = rejectedHandler;

    // Инициализация очередей для каждого потенциального рабочего потока
    for (int i = 0; i < maxPoolSize; i++) {
      taskQueues.add(new LinkedBlockingQueue<>(queueSize));
    }

    // Инициализируем начальное количество потоков (corePoolSize)
    for (int i = 0; i < corePoolSize; i++) {
      addWorker();
    }
  }

  @Override
  public void execute(Runnable command) {
    if (command == null) throw new NullPointerException();
    if (isShutdown.get()) {
      rejectedHandler.rejectedExecution(command, this);
      return;
    }

    // Пробуем добавить задачу в очередь (с балансировкой)
    if (!offerTaskToQueue(command)) {
      // Очереди заполнены, пробуем создать новый поток, если возможно
      mainLock.lock();
      try {
        if (workers.size() < maxPoolSize) {
          // Создаем новый поток и напрямую передаем ему задачу
          Worker worker = addWorker();
          if (worker != null && worker.getTaskQueue().offer(command)) {
            pendingTasks.incrementAndGet();
            System.out.println("[Pool] Task accepted into queue #" + worker.getId() + ": " + command);
            return;
          }
        }
      } finally {
        mainLock.unlock();
      }

      // Все потоки заняты и очереди заполнены - применяем политику отказа
      rejectedHandler.rejectedExecution(command, this);
    }
  }

  @Override
  public <T> Future<T> submit(Callable<T> callable) {
    if (callable == null) throw new NullPointerException();

    FutureTask<T> futureTask = new FutureTask<>(callable);
    execute(futureTask);
    return futureTask;
  }

  @Override
  public void shutdown() {
    isShutdown.set(true);

    mainLock.lock();
    try {
      for (Worker worker : workers.values()) {
        if (worker.getTaskQueue().isEmpty()) {
          worker.interrupt();
        }
      }
    } finally {
      mainLock.unlock();
    }
  }

  @Override
  public void shutdownNow() {
    isShutdown.set(true);

    mainLock.lock();
    try {
      // Прерываем все потоки немедленно
      for (Worker worker : workers.values()) {
        worker.interrupt();
      }

      // Очищаем все очереди
      List<Runnable> tasks = new ArrayList<>();
      for (BlockingQueue<Runnable> queue : taskQueues) {
        queue.drainTo(tasks);
      }
    } finally {
      mainLock.unlock();
    }
  }

  /**
   * Ожидает завершения всех потоков в пуле до указанного таймаута
   * @param timeout время ожидания
   * @param unit единица измерения времени
   * @return true, если все потоки завершились, иначе false
   * @throws InterruptedException если текущий поток был прерван
   */
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long nanos = unit.toNanos(timeout);
    long deadline = System.nanoTime() + nanos;

    mainLock.lock();
    try {
      while (!workers.isEmpty()) {
        nanos = deadline - System.nanoTime();
        if (nanos <= 0L) {
          return false;
        }

        // Прерываем все потоки, чтобы принудительно завершить
        for (Worker worker : new ArrayList<>(workers.values())) {
          worker.interrupt();
        }

        // Ждем немного перед следующей проверкой
        TimeUnit.MILLISECONDS.sleep(100);
      }
      return true;
    } finally {
      mainLock.unlock();
    }
  }

  // Метод для балансировки задач между очередями (Round Robin)
  private boolean offerTaskToQueue(Runnable command) {
    mainLock.lock();
    try {
      // Проверяем, нужно ли создать дополнительные потоки для обеспечения минимального кол-ва резервных
      int activeWorkers = workers.size();
      int busyWorkers = 0;

      // Считаем занятые потоки (имеющие задачи в очереди)
      for (Worker worker : workers.values()) {
        if (!worker.getTaskQueue().isEmpty()) {
          busyWorkers++;
        }
      }

      int spareThreads = activeWorkers - busyWorkers;

      // Если резервных потоков меньше минимального значения, создаем новые
      if (spareThreads < minSpareThreads && activeWorkers < maxPoolSize) {
        int threadsToCreate = Math.min(minSpareThreads - spareThreads, maxPoolSize - activeWorkers);
        for (int i = 0; i < threadsToCreate; i++) {
          addWorker();
        }
      }

      // Алгоритм Round Robin - перебираем все очереди существующих воркеров
      int attempts = workers.size();
      int startIndex = roundRobinCounter.getAndIncrement() % workers.size();

      for (int i = 0; i < attempts; i++) {
        int workerIndex = (startIndex + i) % workers.size();
        Worker worker = workers.get(workerIndex);

        if (worker != null && worker.getTaskQueue().offer(command)) {
          pendingTasks.incrementAndGet();
          System.out.println("[Pool] Task accepted into queue #" + worker.getId() + ": " + command);
          return true;
        }
      }

      return false;
    } finally {
      mainLock.unlock();
    }
  }

  // Создание нового рабочего потока
  private Worker addWorker() {
    mainLock.lock();
    try {
      if (workers.size() >= maxPoolSize) {
        return null;
      }

      int workerId = workerIdGenerator.getAndIncrement();
      BlockingQueue<Runnable> queue = taskQueues.get(workerId % taskQueues.size());
      Worker worker = new Worker(workerId, queue, this, threadFactory);
      workers.put(workerId, worker);
      worker.start();
      return worker;
    } finally {
      mainLock.unlock();
    }
  }

  // Удаление рабочего потока (вызывается самим Worker'ом при таймауте)
  void removeWorker(Worker worker) {
    mainLock.lock();
    try {
      workers.remove(worker.getId());
    } finally {
      mainLock.unlock();
    }
  }

  // Проверка, находится ли пул в состоянии завершения
  boolean isShutdown() {
    return isShutdown.get();
  }

  // Разрешает ли пул завершать потоки по таймауту
  boolean allowThreadTimeout() {
    return true;
  }

  // Получение текущего количества потоков
  int getWorkerCount() {
    return workers.size();
  }

  // Геттеры для параметров пула (используются в Worker)
  int getCorePoolSize() {
    return corePoolSize;
  }

  long getKeepAliveTime() {
    return keepAliveTime;
  }

  TimeUnit getTimeUnit() {
    return timeUnit;
  }

  // Вспомогательный метод для создания пула с настраиваемыми параметрами
  public static class Builder {
    private int corePoolSize = 1;
    private int maxPoolSize = 1;
    private int minSpareThreads = 0;
    private long keepAliveTime = 60;
    private TimeUnit timeUnit = TimeUnit.SECONDS;
    private int queueSize = Integer.MAX_VALUE;
    private ThreadFactory threadFactory;
    private RejectedTaskHandler rejectedHandler;

    public Builder corePoolSize(int corePoolSize) {
      this.corePoolSize = corePoolSize;
      return this;
    }

    public Builder maxPoolSize(int maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
      return this;
    }

    public Builder minSpareThreads(int minSpareThreads) {
      this.minSpareThreads = minSpareThreads;
      return this;
    }

    public Builder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
      this.keepAliveTime = keepAliveTime;
      this.timeUnit = timeUnit;
      return this;
    }

    public Builder queueSize(int queueSize) {
      this.queueSize = queueSize;
      return this;
    }

    public Builder threadFactory(ThreadFactory threadFactory) {
      this.threadFactory = threadFactory;
      return this;
    }

    public Builder rejectedHandler(RejectedTaskHandler rejectedHandler) {
      this.rejectedHandler = rejectedHandler;
      return this;
    }

    public CustomThreadPool build() {
      if (threadFactory == null) {
        threadFactory = new CustomThreadFactory("CustomPool");
      }

      if (rejectedHandler == null) {
        rejectedHandler = new RejectedTaskHandler.AbortPolicy();
      }

      return new CustomThreadPool(
        corePoolSize, maxPoolSize, minSpareThreads,
        keepAliveTime, timeUnit, queueSize,
        threadFactory, rejectedHandler
      );
    }
  }
}
