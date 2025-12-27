package places;

import places.app.PlacesApp;
import places.model.FinalReport;
import places.model.LocationOption;

import java.util.List;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        var app = PlacesApp.createDefault();

        try (Scanner sc = new Scanner(System.in)) {
            System.out.print("Введите название локации (например, \"Цветной проезд\"): ");
            String query = sc.nextLine().trim();
            if (query.isEmpty()) {
                System.out.println("Пустой ввод. Завершение.");
                return;
            }

            List<LocationOption> options = app.searchLocations(query).join();

            if (options.isEmpty()) {
                System.out.println("Ничего не найдено.");
                return;
            }

            System.out.println("\nВарианты локаций:");
            for (int i = 0; i < options.size(); i++) {
                System.out.printf("%d) %s%n", i + 1, options.get(i).displayName());
            }

            int idx = -1;
            while (idx < 1 || idx > options.size()) {
                System.out.print("Выберите номер (1-" + options.size() + "): ");
                String line = sc.nextLine().trim();
                try {
                    idx = Integer.parseInt(line);
                } catch (NumberFormatException e) {
                    idx = -1;
                }
            }

            LocationOption selected = options.get(idx - 1);

            FinalReport report = app.buildReport(selected).join();

            System.out.println("\n========== РЕЗУЛЬТАТ ==========");
            System.out.println(report.toPrettyString());
        }
    }
}
