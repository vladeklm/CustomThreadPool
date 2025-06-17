
public interface RejectedTaskHandler {
  void rejectedExecution(Runnable r, CustomThreadPool executor);

  /**
   * Политика отказа: отклонить задачу с выбросом исключения
   */
  class AbortPolicy implements RejectedTaskHandler {
    @Override
    public void rejectedExecution(Runnable r, CustomThreadPool executor) {
      System.out.println("[Rejected] Task " + r + " was rejected due to overload!");
      throw new RuntimeException("Task rejected: " + r);
    }
  }

  /**
   * Политика отказа: выполнить задачу в потоке, который пытается её отправить
   */
  class CallerRunsPolicy implements RejectedTaskHandler {
    @Override
    public void rejectedExecution(Runnable r, CustomThreadPool executor) {
      if (!executor.isShutdown()) {
        System.out.println("[CallerRuns] Task " + r + " will be executed in the caller thread");
        r.run();
      } else {
        System.out.println("[Rejected] Task " + r + " was rejected (executor shutdown)");
      }
    }
  }

  /**
   * Политика отказа: просто отбросить задачу
   */
  class DiscardPolicy implements RejectedTaskHandler {
    @Override
    public void rejectedExecution(Runnable r, CustomThreadPool executor) {
      System.out.println("[Discarded] Task " + r + " was discarded");
    }
  }
}
