package ai.diffy.transformations;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document
public class Transformation {
    @Id
    public String injectionPoint;
    public String transformationJs;
    public Date updatedAt;

    public Transformation(String injectionPoint, String transformationJs) {
        this.injectionPoint = injectionPoint;
        this.transformationJs = transformationJs;
        this.updatedAt = new Date();
    }

    public String getTransformationJs() {
        return transformationJs;
    }
}
