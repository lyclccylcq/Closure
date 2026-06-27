module com.example.closure {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;


    opens com.example.closure to javafx.fxml;
    exports com.example.closure;
}