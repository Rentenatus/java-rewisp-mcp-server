package com.example;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class Battleship extends javax.swing.JFrame {
    private javax.swing.JLabel[][] cells = new javax.swing.JLabel[8][8];
    private boolean[][] shipGrid = new boolean[8][8];
    private boolean[][] shotGrid = new boolean[8][8];
    private javax.swing.JLabel statusLabel = new javax.swing.JLabel("Schiffe versenken — Feuer frei!");
    private java.util.Random random = new java.util.Random();
    private int shipCellsLeft = 0;
    private int shots = 0;
    private int hits = 0;
    private int gamesWon = 0;
    private int gamesLost = 0;
    private boolean gameOver = false;

    private void initUI() {
        JPanel board = new JPanel(new GridLayout(8, 8, 3, 3));
        board.setBackground(Color.DARK_GRAY);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                JLabel label = new JLabel("", SwingConstants.CENTER);
                label.setFont(new Font("SansSerif", Font.BOLD, 22));
                label.setBackground(Color.WHITE);
                label.setOpaque(true);
                label.setPreferredSize(new Dimension(45, 45));
                final int row = r;
                final int col = c;
                label.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        onCellClick(row, col);
                    }
                });
                cells[r][c] = label;
                board.add(label);
            }
        }
        add(board, BorderLayout.CENTER);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 16));
        statusLabel.setBackground(Color.LIGHT_GRAY);
        statusLabel.setOpaque(true);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private void placeShips() {
        shipGrid = new boolean[8][8];
        shipCellsLeft = 0;
        int[] shipSizes = {4, 3, 3, 2, 2};
        for (int size : shipSizes) {
            boolean placed = false;
            while (!placed) {
                boolean horizontal = random.nextBoolean();
                int row;
                int col;
                if (horizontal) {
                    row = random.nextInt(8);
                    col = random.nextInt(8 - size + 1);
                } else {
                    row = random.nextInt(8 - size + 1);
                    col = random.nextInt(8);
                }
                boolean free = true;
                for (int i = 0; i < size && free; i++) {
                    int r = horizontal ? row : row + i;
                    int c = horizontal ? col + i : col;
                    if (shipGrid[r][c]) {
                        free = false;
                    }
                }
                if (free) {
                    for (int i = 0; i < size; i++) {
                        int r = horizontal ? row : row + i;
                        int c = horizontal ? col + i : col;
                        shipGrid[r][c] = true;
                    }
                    placed = true;
                }
            }
            shipCellsLeft += size;
        }
    }

    private void onCellClick(int row, int col) {
        if (gameOver) {
            resetGame();
            return;
        }
        if (shotGrid[row][col]) {
            return;
        }
        shots++;
        shotGrid[row][col] = true;
        JLabel label = cells[row][col];
        if (shipGrid[row][col]) {
            hits++;
            label.setText("X");
            label.setForeground(Color.WHITE);
            label.setBackground(new Color(200, 60, 60));
            if (hits >= shipCellsLeft) {
                gamesWon++;
                gameOver = true;
                statusLabel.setText("Gewonnen! Alle Treffer in " + shots + " Schüssen. Klicken für neues Spiel.");
                setTitle("Schiffe versenken — Sieg!");
            }
        } else {
            label.setText("~");
            label.setBackground(new Color(60, 100, 200));
            if (shots >= 40) {
                gamesLost++;
                gameOver = true;
                statusLabel.setText("Verloren! " + (shipCellsLeft - hits) + " Schiffsteile übrig. Klicken für neues Spiel.");
                setTitle("Schiffe versenken — Niederlage!");
            }
        }
        if (!gameOver) {
            statusLabel.setText("Schüsse: " + shots + "  Treffer: " + hits + "  Übrig: " + (shipCellsLeft - hits));
        }
    }

    private void resetGame() {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                shotGrid[r][c] = false;
                cells[r][c].setText("");
                cells[r][c].setBackground(Color.WHITE);
            }
        }
        shots = 0;
        hits = 0;
        gameOver = false;
        placeShips();
        statusLabel.setText("Neues Spiel — Feuer frei!");
        setTitle("Schiffe versenken");
    }

    public String getGameStats() {
        int totalGames = gamesWon + gamesLost;
        double winRate = totalGames > 0 ? (double) gamesWon / totalGames * 100 : 0.0;
        return String.format("Siege: %d, Niederlagen: %d, Siegquote: %.1f%%", gamesWon, gamesLost, winRate);
    }

    public void startGame() {
        initUI();
        placeShips();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    public static void main(String[] args) {
        java.awt.EventQueue.invokeLater(() -> new Battleship().startGame());
    }
}
