package ai.diffy.proxy;

import io.netty.handler.codec.http.HttpHeaders;

public class HttpResponse extends HttpMessage {
    private String status;

    public HttpResponse(){}
    public HttpResponse(String status, HttpHeaders headers, String body) {
        super(headers, body);
        this.status = status;
    }
    public HttpResponse(String status, HttpHeaders headers, byte[] rawBody) {
        super(headers, rawBody);
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "\nstatus = "+ status+"\n"+"message =\n"+ super.toString()+"\n";
    }
}
