package com.tzme.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;

/** 浏览器回归专用：仅接受 test profile，使用独立 H2 内存库。 */
public class RegressionPreview {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HelpdeskApplication.class);
        app.setAdditionalProfiles("test");
        app.addInitializers(candidate -> {
            String url=candidate.getEnvironment().getProperty("spring.datasource.url","");
            if (!url.startsWith("jdbc:h2:mem:")) throw new IllegalStateException("浏览器回归仅允许使用 H2 内存库");
        });
        var context = app.run(args);
        var populator = new ResourceDatabasePopulator(new ClassPathResource("db/fixture.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(context.getBean(DataSource.class));
        System.out.println("Regression preview ready (isolated H2 database)");
    }
}
