package ai.diffy.proxy;

import ai.diffy.analysis.DifferenceResult;
import ai.diffy.functional.algebra.monoids.functions.SeptaOperator;
import ai.diffy.functional.functions.Try;
import ai.diffy.lifter.AnalysisRequest;
import ai.diffy.lifter.LiftResponseInput;
import ai.diffy.lifter.Message;
import ai.diffy.util.Future;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;
import java.util.Optional;

import java.util.concurrent.CompletableFuture;

public class MulticastProxy {
    private static final Logger log = LoggerFactory.getLogger(MulticastProxy.class);
    private static final CompletableFuture<HttpResponse> empty =
            CompletableFuture.completedFuture(new HttpResponse(HttpResponseStatus.OK.toString(), EmptyHttpHeaders.INSTANCE, ""));

    public static final SeptaOperator<HttpRequest,
            HttpRequest, CompletableFuture<HttpResponse>,
            HttpRequest, CompletableFuture<HttpResponse>,
            HttpRequest, CompletableFuture<HttpResponse>,
            AnalysisRequest, CompletableFuture<Optional<DifferenceResult>>,
            HttpRequest, Message,
            LiftResponseInput, Message,
            Tuple3<CompletableFuture<HttpResponse>, CompletableFuture<HttpResponse>, CompletableFuture<HttpResponse>>, CompletableFuture<HttpResponse>,
            CompletableFuture<HttpResponse>> Operator =
            (
                    primary,
                    secondary,
                    candidate,
                    analyzer,
                    liftRequest,
                    liftResponse,
                    responsePicker
            ) -> (HttpRequest request) -> {
                switch (request.getRoutingMode()) {
                    case primary : return primary.apply(request);
                    case secondary : return secondary.apply(request);
                    case candidate : return candidate.apply(request);
                    case none : return empty;
                    case all : {

                        CompletableFuture<HttpResponse> pr = primary.apply(request);
                        CompletableFuture<HttpResponse> cr = Future.getAfter(pr, () -> candidate.apply(request));
                        CompletableFuture<HttpResponse> sr = Future.getAfter(cr, () -> secondary.apply(request));

                        sr.thenApply(msgS -> Try.of(() -> {
                            Message r = liftRequest.apply(request);
                            String requestPath = request.getPath();
                            Message c = liftResponse.apply(new LiftResponseInput(cr.get(), requestPath));
                            Message p = liftResponse.apply(new LiftResponseInput(pr.get(), requestPath));
                            Message s = liftResponse.apply(new LiftResponseInput(sr.get(), requestPath));
                            log.info("Lifting completed for path: {}", requestPath);
                            return new AnalysisRequest(r, c, p, s);
                        })).thenApply(tryRequest -> {
                            if (!tryRequest.isNormal()) {
                                log.error("Failed to lift request/responses for path: {}", request.getPath(), tryRequest.getThrowable());
                                return tryRequest;
                            }
                            log.info("Starting analysis for path: {}", request.getPath());
                            return tryRequest.map(analysisRequest -> {
                                CompletableFuture<Optional<DifferenceResult>> future = analyzer.apply(analysisRequest);
                                future.whenComplete((result, error) -> {
                                    if (error != null) {
                                        log.error("Analysis failed for path: {}", request.getPath(), error);
                                    } else if (result != null && result.isPresent()) {
                                        log.info("Analysis saved for path: {}, total diffs: {}", 
                                            request.getPath(), result.get().differences.size());
                                    } else {
                                        log.info("Analysis completed for path: {}, no differences found", request.getPath());
                                    }
                                });
                                return future;
                            });
                        });
                        return responsePicker.apply(Tuples.of(pr, cr, sr));
                    }
                }
                return empty;
            };
}
