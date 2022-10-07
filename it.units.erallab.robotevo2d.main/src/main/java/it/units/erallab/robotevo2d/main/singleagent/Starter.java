/*
 * Copyright 2022 eric
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.units.erallab.robotevo2d.main.singleagent;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import it.units.erallab.mrsim2d.builder.NamedBuilder;
import it.units.erallab.mrsim2d.builder.StringNamedParamMap;
import it.units.erallab.mrsim2d.core.EmbodiedAgent;
import it.units.erallab.mrsim2d.core.engine.Engine;
import it.units.erallab.mrsim2d.viewer.VideoBuilder;
import it.units.erallab.mrsim2d.viewer.VideoUtils;
import it.units.erallab.robotevo2d.main.builder.MapperBuilder;
import it.units.malelab.jgea.core.QualityBasedProblem;
import it.units.malelab.jgea.core.listener.*;
import it.units.malelab.jgea.core.order.PartialComparator;
import it.units.malelab.jgea.core.solver.Individual;
import it.units.malelab.jgea.core.solver.IterativeSolver;
import it.units.malelab.jgea.core.solver.SolverException;
import it.units.malelab.jgea.core.solver.state.POSetPopulationState;
import it.units.malelab.jgea.core.util.ImagePlotters;
import it.units.malelab.jgea.core.util.Misc;
import it.units.malelab.jgea.core.util.Pair;
import it.units.malelab.jgea.telegram.TelegramProgressMonitor;
import it.units.malelab.jgea.telegram.TelegramUpdater;
import it.units.malelab.jgea.tui.TerminalMonitor;

import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static it.units.malelab.jgea.core.listener.NamedFunctions.*;

/**
 * @author "Eric Medvet" on 2022/08/11 for 2d-robot-evolution
 */
public class Starter implements Runnable {

  private final static Logger L = Logger.getLogger(Starter.class.getName());

  private final Configuration configuration;
  private final NamedBuilder<Object> nb;

  public Starter(Configuration configuration, NamedBuilder<Object> nb) {
    this.configuration = configuration;
    this.nb = nb;
  }

  private static ListenerFactory<POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, Map<String, Object>> getCsvPrinter(
      FileSaver<?> fileSaver,
      List<NamedFunction<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, ?>> nonVisualFunctions,
      List<NamedFunction<? super Map<String, Object>, ?>> keysFunctions
  ) {
    @SuppressWarnings("unchecked") Function<Object, String> serializer =
        (Function<Object, String>) fileSaver.serializer();
    List<NamedFunction<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, ?>> fileFunctions =
        nonVisualFunctions;
    if (fileSaver.serializer() != null) {
      NamedFunction<? super Individual<?, Supplier<EmbodiedAgent>, ?>, ?> gSerializer = f(
          "g",
          "%s",
          i -> serializer.apply(i.genotype())
      );
      //noinspection unchecked,rawtypes
      fileFunctions = Misc.concat(List.of(nonVisualFunctions, best().then((List) List.of(gSerializer))));
    }
    return new CSVPrinter<>(fileFunctions, keysFunctions, new File(fileSaver.fileName()));
  }

  private static AccumulatorFactory<POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, BufferedImage, Map<String,
      Object>> getPlotter(
      Experiment<?, ?> experiment
  ) {
    @SuppressWarnings("unchecked") NamedFunction<Object, Double> qFunction =
        ((NamedFunction<Object, Double>) experiment.qExtractor());
    return new TableBuilder<POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, Number, Map<String, Object>>(List.of(
        iterations(),
        best().then(fitness()).then(qFunction),
        min(Double::compare).of(each(qFunction.of(fitness()))).of(all()),
        median(Double::compare).of(each(qFunction.of(fitness()))).of(all())
    ), List.of()).then(t -> ImagePlotters.xyLines(600, 400).apply(t));
  }

  private static TelegramUpdater<POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, Map<String, Object>> getTelegramUpdater(
      Experiment<?, ?> experiment,
      Supplier<Engine> engineSupplier,
      String telegramBotId,
      long telegramChatId
  ) {
    List<AccumulatorFactory<POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, ?, Map<String, Object>>> accumulators = new ArrayList<>();
    accumulators.add(getPlotter(experiment));
    if (experiment.videoSaver() != null) {
      experiment.videoTasks().forEach(t -> accumulators.add(getVideoMaker(engineSupplier, experiment.videoSaver(), t)));
    }
    return new TelegramUpdater<>(accumulators, telegramBotId, telegramChatId);
  }

  private static AccumulatorFactory<POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, File, Map<String, Object>> getVideoMaker(
      Supplier<Engine> engineSupplier, VideoSaver videoSaver, VideoTask videoTask
  ) {
    return AccumulatorFactory.last((state, keys) -> {
      File file;
      L.info(String.format("Doing video for %s", videoTask.map().npm("task").toString()));
      try {
        file = File.createTempFile("robot-video", ".mp4");
        VideoBuilder videoBuilder = new VideoBuilder(
            videoSaver.w(),
            videoSaver.h(),
            videoSaver.startTime(),
            videoSaver.endTime(),
            videoSaver.frameRate(),
            VideoUtils.EncoderFacility.valueOf(videoSaver.codec().toUpperCase()),
            file,
            videoSaver.drawer().apply(videoTask.map().npm("task").toString())
        );
        Supplier<EmbodiedAgent> agent = Misc.first(state.getPopulation().firsts()).solution();
        videoTask.task().run(agent, engineSupplier.get(), videoBuilder);
        file = videoBuilder.get();
        file.deleteOnExit();
      } catch (IOException ioException) {
        L.warning(String.format("Cannot save video of best: %s", ioException));
        return null;
      }
      return file;
    });
  }

  public static void main(String[] args) {
    NamedBuilder<Object> nb = PreparedNameBuilder.get();
    Configuration configuration = new Configuration();
    try {
      JCommander.newBuilder()
          .addObject(configuration)
          .build()
          .parse(args);
      new Starter(configuration, nb).run();
    } catch (ParameterException e) {
      e.usage();
      L.severe(String.format("Cannot read command line options: %s", e));
      System.exit(-1);
    } catch (RuntimeException e) {
      L.severe(e.getClass().getSimpleName() + ": " + e.getMessage());
      System.exit(-1);
    }
  }

  @Override
  public void run() {
    //check if it's just an help invocation
    if (configuration.showExpFileName) {
      System.out.println(NamedBuilder.prettyToString(nb, true));
      System.exit(0);
    }
    //read experiment description
    String expDescription;
    if (configuration.experimentDescriptionFilePath.isEmpty()) {
      L.config("Using default experiment description");
      InputStream inputStream = getClass().getResourceAsStream("/example-experiment.txt");
      if (inputStream == null) {
        throw new IllegalArgumentException("Cannot find default experiment description");
      } else {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
          expDescription = br.lines().collect(Collectors.joining());
        } catch (IOException e) {
          throw new IllegalArgumentException(String.format("Cannot read default experiment description: %s", e));
        }
      }
    } else {
      L.config(String.format("Using provided experiment description: %s", configuration.experimentDescriptionFilePath));
      try (BufferedReader br = new BufferedReader(new FileReader(configuration.experimentDescriptionFilePath))) {
        expDescription = br.lines().collect(Collectors.joining());
      } catch (IOException e) {
        throw new IllegalArgumentException(String.format(
            "Cannot read provided experiment description at %s: %s",
            configuration.experimentDescriptionFilePath,
            e
        ));
      }
    }
    Experiment<?, ?> experiment = (Experiment<?, ?>) nb.build(expDescription);
    //read telegram credentials file
    String telegramBotId = "";
    long telegramChatId = 0;
    if (!configuration.telegramCredentialsFilePath.isEmpty()) {
      try (BufferedReader br = new BufferedReader(new FileReader(configuration.telegramCredentialsFilePath))) {
        List<String> lines = br.lines().toList();
        if (lines.size() < 1) {
          throw new IllegalArgumentException("Invalid telegram credential file with 0 lines");
        }
        String[] pieces = lines.get(0).split("\\s");
        telegramBotId = pieces[0];
        telegramChatId = Long.parseLong(pieces[1]);
        L.config(String.format("Using provided telegram credentials: %s", configuration.telegramCredentialsFilePath));
      } catch (IOException e) {
        throw new IllegalArgumentException(String.format(
            "Cannot read telegram credentials at %s: %s",
            configuration.experimentDescriptionFilePath,
            e
        ));
      }
    }
    //create engine supplier
    Supplier<Engine> engineSupplier = () -> ServiceLoader.load(Engine.class)
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Cannot instantiate an engine"));
    //create executor
    ExecutorService executorService = Executors.newFixedThreadPool(configuration.nOfThreads);
    //create common listeners and progress monitor
    List<NamedFunction<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, ?>> basicFunctions =
        List.of(
            iterations(),
            births(),
            fitnessEvaluations(),
            elapsedSeconds()
        );
    @SuppressWarnings("unchecked") NamedFunction<Object, Double> qFunction =
        ((NamedFunction<Object, Double>) experiment.qExtractor());
    List<NamedFunction<? super Individual<?, Supplier<EmbodiedAgent>, ?>, ?>> individualFunctions = List.of(
        size().of(genotype()),
        f("genotype.birth.iteration", "%4d", Individual::genotypeBirthIteration),
        fitness().then(qFunction)
    );
    List<NamedFunction<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, ?>> visualFunctions =
        List.of(hist(8).of(each(qFunction.of(fitness()))).of(all()));
    @SuppressWarnings({"rawtypes", "unchecked"}) List<NamedFunction<? super POSetPopulationState<?,
        Supplier<EmbodiedAgent>, ?>, ?>> nonVisualFunctions = Misc.concat(
        List.of(basicFunctions, best().then((List) individualFunctions)));
    List<NamedFunction<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, ?>> screenFunctions = Misc.concat(
        List.of(nonVisualFunctions, visualFunctions));
    List<NamedFunction<? super Map<String, Object>, ?>> keysFunctions = List.of(
        attribute("solver"),
        attribute("mapper"),
        attribute("target"),
        attribute("task"),
        attribute("comparator"),
        attribute("randomGenerator")
    );
    //prepare terminal monitor
    TerminalMonitor<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, Map<String, Object>> terminalMonitor = new TerminalMonitor<>(
        screenFunctions,
        List.of(),
        List.of(
            new Pair<>(
                iterations(),
                best().then(fitness()).then(qFunction)
            )
        )
    );
    //preapare factories
    List<ListenerFactory<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, Map<String, Object>>> factories = new ArrayList<>();
    factories.add(terminalMonitor);
    if (experiment.bestFileSaver() != null && experiment.bestFileSaver()
        .fileName() != null && !experiment.bestFileSaver().fileName().isEmpty()) {
      factories.add(getCsvPrinter(experiment.bestFileSaver(), nonVisualFunctions, keysFunctions));
    }
    if (!telegramBotId.isEmpty()) {
      factories.add(getTelegramUpdater(experiment, engineSupplier, telegramBotId, telegramChatId));
    }
    ListenerFactory<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>, Map<String, Object>> factory =
        ListenerFactory.all(
            factories);
    //build progress monitor
    ProgressMonitor progressMonitor = terminalMonitor;
    if (!telegramBotId.isEmpty()) {
      progressMonitor = progressMonitor.and(new TelegramProgressMonitor(telegramBotId, telegramChatId));
    }
    //iterate over runs
    for (int i = 0; i < experiment.runs().size(); i++) {
      Run<?, ?> run = experiment.runs().get(i);
      progressMonitor.notify(
          (float) i / (float) experiment.runs().size(),
          String.format(
              "Starting %d/%d run:%n%s",
              i + 1,
              experiment.runs().size(),
              StringNamedParamMap.prettyToString(run.map(), 40)
          )
      );
      //build solver
      IterativeSolver<? extends POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>,
          QualityBasedProblem<Supplier<EmbodiedAgent>, ?>, Supplier<EmbodiedAgent>> solver;
      try {
        //noinspection unchecked,rawtypes
        solver = run.solverBuilder().build(
            (MapperBuilder) run.mapper(),
            (Supplier<EmbodiedAgent>) () -> (EmbodiedAgent) nb.build(run.map().npm("target"))
        );
      } catch (RuntimeException e) {
        L.warning(String.format("Cannot instantiate solver %s: %s", run.map().npm("solver"), e));
        e.printStackTrace();
        break;
      }
      //build problem
      @SuppressWarnings("unchecked") QualityBasedProblem<Supplier<EmbodiedAgent>, ?> problem =
          QualityBasedProblem.create(
              s -> run.task().run(s, engineSupplier.get()),
              (PartialComparator<Object>) run.comparator()
          );
      //build listener
      Listener<? super POSetPopulationState<?, Supplier<EmbodiedAgent>, ?>> listener =
          factory.build(Map.ofEntries(
              Map.entry("solver", run.map().npm("solver")),
              Map.entry("mapper", run.map().npm("mapper")),
              Map.entry("target", run.map().npm("target")),
              Map.entry("task", run.map().npm("task")),
              Map.entry("comparator", run.map().npm("comparator")),
              Map.entry("randomGenerator", run.map().npm("randomGenerator"))
          ));
      //do optimization
      try {
        Instant startingT = Instant.now();
        Collection<Supplier<EmbodiedAgent>> solutions = solver.solve(
            problem,
            run.randomGenerator(),
            executorService,
            listener
        );
        double elapsedT = Duration.between(startingT, Instant.now()).toMillis() / 1000d;
        String msg = String.format(
            "%d/%d run done in %.2fs, found %d solutions",
            i + 1,
            experiment.runs().size(),
            elapsedT,
            solutions.size()
        );
        L.info(msg);
        progressMonitor.notify((float) (i + 1) / (float) experiment.runs().size(), msg);
      } catch (SolverException | RuntimeException e) {
        L.warning(String.format("Cannot solve %s: %s", run.map(), e));
        break;
      }
    }
    factory.shutdown();
    executorService.shutdown();
  }
}
