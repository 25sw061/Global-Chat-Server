FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY . .
RUN javac Server.java
CMD ["java", "Server"]