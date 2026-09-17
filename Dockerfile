# Multi-stage Docker build for Render deployment
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app
COPY . .
RUN mkdir -p bin && javac -d bin -cp "lib/*" CropAdvisoryApp.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/bin ./bin
COPY --from=builder /app/lib ./lib
COPY --from=builder /app/web ./web
COPY --from=builder /app/*.txt ./

EXPOSE 8080
ENV PORT=8080

CMD ["java", "-cp", "bin:lib/*", "CropAdvisoryApp"]
