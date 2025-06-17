import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class Main {
  public static void main(String[] args) throws InterruptedException {
    // Устанавливаем кодировку для системного вывода
    System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

    // Создаем пул потоков с настраиваемыми параметрами
    CustomThreadPool pool = new CustomThreadPool.Builder()
      .corePoolSize(2)
      .maxPoolSize(4)
      .minSpareThreads(1)
      .queueSize(5)
      .keepAliveTime(5, TimeUnit.SECONDS)
      .threadFactory(new CustomThreadFactory("DemoPool"))
      .rejectedHandler(new RejectedTaskHandler.CallerRunsPolicy())
      .build();

    System.out.println("===== Тест 1: Нормальная работа пула =====");
    // Создаем и отправляем несколько задач
    for (int i = 0; i < 5; i++) {
      final int taskId = i;
      pool.execute(new DemoTask(taskId, 1000));
    }

    // Ждем завершения первой партии задач
    Thread.sleep(3000);

    System.out.println("\n===== Тест 2: Высокая нагрузка (переполнение очереди) =====");
    // Отправляем много задач, чтобы переполнить очередь и вызвать обработчик отказа
    for (int i = 0; i < 15; i++) {
      final int taskId = i + 100;
      try {
        pool.execute(new DemoTask(taskId, 500));
      } catch (RuntimeException e) {
        System.out.println("Исключение при добавлении задачи " + taskId + ": " + e.getMessage());
      }
    }

    // Ждем завершения задач
    Thread.sleep(5000);

    System.out.println("\n===== Тест 3: Таймаут бездействия потоков =====");
    // Ждем, чтобы потоки вышли за keepAliveTime и завершились
    System.out.println("Ожидание таймаута бездействия потоков...");
    Thread.sleep(6000);

    System.out.println("\n===== Тест 4: Shutdown =====");
    // Отправляем еще несколько задач
    for (int i = 0; i < 3; i++) {
      final int taskId = i + 200;
      pool.execute(new DemoTask(taskId, 1000));
    }

    // Ждем немного и завершаем пул
    Thread.sleep(500);
    System.out.println("Вызываем shutdown()...");
    pool.shutdown();

    // Ждем завершения всех задач
    Thread.sleep(3000);

    System.out.println("\n===== Тест 5: Попытка добавить задачу после shutdown =====");
    try {
      pool.execute(new DemoTask(999, 1000));
    } catch (Exception e) {
      System.out.println("Исключение при добавлении задачи после shutdown: " + e.getMessage());
    }

    // Ждем дополнительное время для завершения всех логов
    Thread.sleep(1000);
    System.out.println("\n===== Тесты завершены =====");
  }

  // Демонстрационная задача с уникальным ID и заданным временем выполнения
  static class DemoTask implements Runnable {
    private final int id;
    private final long executionTime;

    public DemoTask(int id, long executionTime) {
      this.id = id;
      this.executionTime = executionTime;
    }

    @Override
    public void run() {
      try {
        System.out.println("Task-" + id + " начинает выполнение в " + Thread.currentThread().getName());
        // Имитация работы
        Thread.sleep(executionTime);
        System.out.println("Task-" + id + " завершена в " + Thread.currentThread().getName());
      } catch (InterruptedException e) {
        System.out.println("Task-" + id + " прервана в " + Thread.currentThread().getName());
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public String toString() {
      return "Task-" + id;
    }
  }
}
