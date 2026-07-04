package com.skew.engine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.ai.google.genai.api-key=test",
		"spring.ai.vectorstore.pgvector.initialize-schema=false",
		"spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.PgVectorStoreAutoConfiguration",
		"spring.datasource.url=jdbc:h2:mem:skewtest;MODE=PostgreSQL;DATABASE_TO_UPPER=false",

		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false"
})
class SkewEngineApplicationTests {

	@Test
	void contextLoads() {
	}

}
