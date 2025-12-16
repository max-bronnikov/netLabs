
./gradlew build

сервер
java -jar build/libs/fileTransfer-1.0-SNAPSHOT.jar server 9000

клиент
java -jar build/libs/fileTransfer-1.0-SNAPSHOT.jar client 127.0.0.1 9000 <файл>

сервер создает каталог uploads и загружает файл в него

