package com.example.mcpdebugger;

import com.example.mcpdebugger.persistence.ConfigRepository;
import com.example.mcpdebugger.ui.MainWindow;
import javax.swing.SwingUtilities;

public class AppLauncher {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainWindow(new ConfigRepository()).setVisible(true));
    }
}
