package workshop.stations;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.rxjava.core.AbstractVerticle;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import rx.Notification;
import rx.Observable;
import rx.functions.Actions;
import rx.observables.StringObservable;
import rx.schedulers.Schedulers;
import workshop.model.Station;
import workshop.model.Stop;
import workshop.model.Train;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import static java.util.logging.Level.SEVERE;
import static rx.Completable.fromFuture;
import static workshop.shared.Constants.DATAGRID_HOST;
import static workshop.shared.Constants.DATAGRID_PORT;
import static workshop.shared.Constants.STATIONS_INJECTOR_URI;
import static workshop.shared.Constants.STATION_BOARDS_CACHE_NAME;

public class StationsInjector extends AbstractVerticle {

  private static final Logger log = Logger.getLogger(StationsInjector.class.getName());

  private RemoteCacheManager client;

  @Override
  public void start(Future<Void> future) throws Exception {
    Router router = Router.router(vertx.getDelegate());
    router.get(STATIONS_INJECTOR_URI).handler(this::inject);

    vertx
      .<RemoteCacheManager>rxExecuteBlocking(fut -> fut.complete(createClient()))
      .doOnSuccess(remoteClient -> client = remoteClient)
      .subscribe(res ->
        // TODO: Best practice for chaining rx-style vert.x web server startup and duplicate
        vertx.getDelegate()
          .createHttpServer()
          .requestHandler(router::accept)
          .listen(8080, ar -> {
            if (ar.succeeded()) {
              log.info("Station injector HTTP server started");
              future.complete();
            } else {
              log.severe("Station injector HTTP server failed to start");
              future.fail(ar.cause());
            }
          }),
        future::fail);
  }

  @Override
  public void stop() throws Exception {
    if (Objects.nonNull(client))
      client.stop();
  }

  // TODO: Duplicate
  private void inject(RoutingContext ctx) {
    vertx
      .<RemoteCache<String, Stop>>rxExecuteBlocking(fut -> fut.complete(client.getCache(STATION_BOARDS_CACHE_NAME)))
      // Remove data on start, to start clean
      .map(stations -> fromFuture(stations.clearAsync()).to(x -> stations))
      .subscribe(stations -> {
        vertx.setPeriodic(5000L, l ->
          vertx.executeBlocking(fut -> {
            log.info(String.format("Progress: stored=%d%n", stations.size()));
            fut.complete();
          }, false, ar -> {}));

        rxReadGunzippedTextResource("cff-stop-2016-02-29__.jsonl.gz")
          .map(StationsInjector::toEntry)
          .repeatWhen(notification -> notification.map(terminal -> {
            log.info("Reached end of file, clear and restart");
            stations.clear(); // If it reaches the end of the file, start again
            return Notification.createOnNext(null);
          }))
          // TODO: Should be a flatmapObservable call putAsync wrapped with Completable?
          .doOnNext(entry -> stations.put(entry.getKey(), entry.getValue()))
          .subscribe(Actions.empty(),
            t -> log.log(SEVERE, "Error while loading", t));

        ctx.response().end("Injector started");
      });
  }

  // TODO: Duplicate
  private static RemoteCacheManager createClient() {
    try {
      RemoteCacheManager client = new RemoteCacheManager(
        new ConfigurationBuilder().addServer()
          .host(DATAGRID_HOST)
          .port(DATAGRID_PORT)
          .marshaller(ProtoStreamMarshaller.class)
          .build());

      SerializationContext ctx = ProtoStreamMarshaller.getSerializationContext(client);

      ctx.registerProtoFiles(FileDescriptorSource.fromResources("station-board.proto"));
      ctx.registerMarshaller(new Stop.Marshaller());
      ctx.registerMarshaller(new Station.Marshaller());
      ctx.registerMarshaller(new Train.Marshaller());
      return client;
    } catch (Exception e) {
      log.log(Level.SEVERE, "Error creating client", e);
      throw new RuntimeException(e);
    }
  }

  // TODO: Duplicate
  private static Observable<String> rxReadGunzippedTextResource(String resource) {
    Objects.requireNonNull(resource);
    URL url = StationsInjector.class.getClassLoader().getResource(resource);
    Objects.requireNonNull(url);

    return StringObservable
      .using(() -> {
        InputStream inputStream = url.openStream();
        InputStream gzipStream = new GZIPInputStream(inputStream);
        Reader decoder = new InputStreamReader(gzipStream, StandardCharsets.UTF_8);
        return new BufferedReader(decoder);
      }, StringObservable::from)
      .compose(StringObservable::byLine)
      .subscribeOn(Schedulers.io());
  }

  private static Map.Entry<String, Stop> toEntry(String line) {
    JsonObject json = new JsonObject(line);
    String trainName = json.getString("name");
    String trainTo = json.getString("to");
    String trainCat = json.getString("category");
    String trainOperator = json.getString("operator");

    Train train = Train.make(trainName, trainTo, trainCat, trainOperator);

    JsonObject jsonStop = json.getJsonObject("stop");
    JsonObject jsonStation = jsonStop.getJsonObject("station");
    long stationId = Long.parseLong(jsonStation.getString("id"));
    String stationName = jsonStation.getString("name");
    Station station = Station.make(stationId, stationName);

    Date departureTs = new Date(jsonStop.getLong("departureTimestamp") * 1000);
    int delayMin = orNull(jsonStop.getValue("delay"), 0);

    String stopId = String.format(
      "%s/%s/%s/%s",
      stationId, trainName, trainTo, jsonStop.getString("departure")
    );

    Stop stop = Stop.make(train, delayMin, station, departureTs);

    return new AbstractMap.SimpleImmutableEntry<>(stopId, stop);
  }

  // TODO: Duplicate
  @SuppressWarnings("unchecked")
  private static <T> T orNull(Object obj, T defaultValue) {
    return Objects.isNull(obj) ? defaultValue : (T) obj;
  }

}