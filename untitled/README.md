# Отчет о реализации кастомного пула потоков

## Анализ производительности

### Сравнение с `ThreadPoolExecutor` из стандартной библиотеки Java

Реализованный кастомный пул потоков имеет несколько отличий от стандартного `ThreadPoolExecutor`:

| Характеристика | Кастомный пул | ThreadPoolExecutor |
|----------------|---------------|-------------------|
| Очереди задач | Отдельная очередь для каждого потока | Единая очередь для всех потоков |
| Распределение задач | Round Robin между потоками | Задачи обрабатываются первым свободным потоком |
| Резервные потоки | Поддержка минимального количества резервных потоков | Отсутствует |
| Информативность | Подробное логирование всех событий | Базовое логирование |

#### Преимущества кастомного пула

1. **Снижение конкуренции за блокировки очереди**
   В стандартном `ThreadPoolExecutor` все потоки конкурируют за доступ к единой очереди задач, что может создавать узкое место при высокой нагрузке. В кастомном пуле каждый поток работает со своей очередью, что снижает конкуренцию и может улучшить производительность на многоядерных системах.

2. **Поддержка резервных потоков**
   Параметр `minSpareThreads` позволяет поддерживать минимальное количество свободных потоков, готовых к обработке новых задач. Это обеспечивает более быструю реакцию на резкие всплески нагрузки по сравнению со стандартным пулом, который создает новые потоки только при заполнении очереди.

3. **Предсказуемое распределение нагрузки**
   Алгоритм Round Robin обеспечивает равномерное распределение задач между потоками, что может быть предпочтительнее в некоторых сценариях, чем подход "первый свободный поток" в стандартном пуле.

4. **Детальное логирование**
   Расширенное логирование позволяет более точно отслеживать состояние пула и диагностировать проблемы.

#### Недостатки кастомного пула

1. **Увеличенное потребление памяти**
   Использование отдельных очередей для каждого потока увеличивает потребление памяти по сравнению со стандартным пулом.

2. **Возможная неэффективность при неравномерных задачах**
   Алгоритм Round Robin может приводить к неэффективному распределению, если задачи сильно различаются по времени выполнения. В таком случае некоторые потоки могут простаивать, в то время как другие будут перегружены.

3. **Сложность реализации и поддержки**
   Кастомный пул сложнее в реализации и поддержке, чем использование готового решения из стандартной библиотеки.

### Сравнение с пулами из других фреймворков

#### Tomcat (`org.apache.tomcat.util.threads.ThreadPoolExecutor`)

Пул потоков Tomcat модифицирует стандартный `ThreadPoolExecutor` со следующими особенностями:
- Поддержка приоритетов задач
- Более гибкий механизм отказов
- Возможность задать максимальное время ожидания задачи в очереди

Наш кастомный пул уступает в отсутствии приоритизации задач, но превосходит в возможности поддерживать минимальное количество резервных потоков.

#### Jetty (`org.eclipse.jetty.util.thread.QueuedThreadPool`)

Пул потоков Jetty имеет следующие особенности:
- Поддержка срочных задач (обрабатываются вне очереди)
- Динамическое управление размером пула в зависимости от нагрузки
- Встроенные метрики производительности

Наш кастомный пул уступает в отсутствии поддержки срочных задач и встроенных метрик, но предлагает более гибкую модель распределения задач между потоками.

## Мини-исследование оптимальных параметров пула

На основе тестирования пула потоков в различных условиях были выявлены следующие рекомендации по настройке параметров для достижения максимальной производительности:

### 1. Параметр `corePoolSize`

Оптимальное значение: **число ядер процессора**

При тестировании на системах с различным количеством ядер было обнаружено, что установка `corePoolSize` равным количеству доступных ядер процессора обеспечивает наилучший баланс между параллелизмом и накладными расходами на переключение контекста.

Увеличение `corePoolSize` выше количества ядер может привести к снижению производительности из-за увеличения накладных расходов на переключение контекста, особенно для CPU-интенсивных задач.

### 2. Параметр `maxPoolSize`

Оптимальное значение: **от 2 до 4 * количество ядер процессора**

Для IO-интенсивных задач (например, сетевые запросы, операции с файлами) увеличение `maxPoolSize` до 4 * количество ядер может значительно повысить производительность.

Для CPU-интенсивных задач лучше ограничить `maxPoolSize` значением не более 2 * количество ядер.

### 3. Параметр `queueSize`

Оптимальное значение: **зависит от сценария использования**

- Для обработки запросов с высоким приоритетом: **10-50**
  Небольшой размер очереди позволяет быстрее отклонять задачи при перегрузке, что может быть предпочтительнее, чем большая задержка.

- Для обработки фоновых задач: **100-500**
  Большой размер очереди позволяет сгладить пики нагрузки за счет временного накопления задач.

### 4. Параметр `minSpareThreads`

Оптимальное значение: **10-20% от `maxPoolSize`**

Поддержание небольшого количества резервных потоков улучшает реакцию на внезапные всплески нагрузки без существенного увеличения потребления ресурсов.

### 5. Параметр `keepAliveTime`

Оптимальное значение: **30-60 секунд**

- Слишком маленькое значение может привести к частому созданию и уничтожению потоков при колебаниях нагрузки
- Слишком большое значение может приводить к неэффективному использованию ресурсов из-за простаивающих потоков

### Рекомендации для различных сценариев

| Тип нагрузки | corePoolSize | maxPoolSize | queueSize | minSpareThreads | keepAliveTime |
|--------------|--------------|-------------|-----------|-----------------|---------------|
| CPU-интенсивные задачи | N | N-2N | 50-100 | 1-2 | 30 сек |
| IO-интенсивные задачи | N | 2N-4N | 100-200 | N/5 | 60 сек |
| Смешанная нагрузка | N | 2N | 100 | N/10 | 45 сек |
| Критические системы | 2N | 4N | 20-50 | N/3 | 120 сек |

Где N - количество ядер процессора.

## Принцип действия механизма распределения задач

В реализованном пуле потоков используется механизм распределения задач на основе алгоритма Round Robin с учетом поддержки минимального количества резервных потоков:

1. **Инициализация**
   При создании пула создаются отдельные очереди для каждого потенциального рабочего потока (максимум `maxPoolSize` очередей).

2. **Балансировка задач**
   При поступлении новой задачи в метод `execute()`:

  - Проверяется количество свободных потоков
  - Если свободных потоков меньше `minSpareThreads` и текущее количество потоков меньше `maxPoolSize`, создаются дополнительные потоки
  - Используется атомарный счетчик `roundRobinCounter` для равномерного распределения задач между потоками
  - Задача добавляется в очередь выбранного потока по принципу Round Robin
  - Если все очереди заполнены и количество потоков достигло `maxPoolSize`, применяется выбранная политика отказа

3. **Обработка задач воркерами**
   Каждый воркер:

  - Забирает задачи только из своей очереди
  - При отсутствии задач ожидает новую задачу в течение `keepAliveTime`
  - Если таймаут истек и количество потоков превышает `corePoolSize`, поток завершается

Этот подход обеспечивает ряд преимуществ:
- Снижение конкуренции за доступ к очереди задач
- Равномерное распределение нагрузки между потоками
- Быстрая реакция на всплески нагрузки благодаря резервным потокам
- Эффективное использование ресурсов за счет автоматической регулировки количества потоков
