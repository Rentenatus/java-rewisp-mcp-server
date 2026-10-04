package com.example;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class TicTacToe extends JFrame {
    private JLabel[][] cells = new JLabel[3][3];
    private JLabel statusLabel = new JLabel("Tic-Tac-Toe — Player X starts. X:0 O:0 D:0");
    private Stone currentPlayer = new XStone();

    public TicTacToe() {
        setTitle("Tic-Tac-Toe — X vs O");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        initUI();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void initUI() {
        JPanel board = new JPanel(new GridLayout(3, 3, 5, 5));
        board.setBackground(Color.DARK_GRAY);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                JLabel label = new JLabel("", SwingConstants.CENTER);
                label.setFont(new Font("SansSerif", Font.BOLD, 60));
                label.setBackground(Color.WHITE);
                label.setOpaque(true);
                label.setPreferredSize(new Dimension(100, 100));
                final int row = r;
                final int col = c;
                label.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        onCellClick(row, col);
                    }
                });
                cells[r][c] = label;
                stones[r][c] = new EmptyStone();
                board.add(label);
            }
        }
        add(board, BorderLayout.CENTER);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 18));
        statusLabel.setBackground(Color.LIGHT_GRAY);
        statusLabel.setOpaque(true);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private void onCellClick(int row, int col) {
        if (gameOver) {
            resetBoard();
            return;
        }
        if (!stones[row][col].isEmpty()) {
            return;
        }
        placeMark(row, col);
        if (checkWin()) {
            if (currentPlayer.getSymbol() == 'X') {
                xWins++;
            } else {
                oWins++;
            }
            gameOver = true;
            statusLabel.setText("Player " + currentPlayer.getSymbol() + " wins! Click to play again. X:" + xWins + " O:" + oWins + " D:" + draws);
            setTitle("Player " + currentPlayer.getSymbol() + " wins!");
            animateWin();
        } else if (isBoardFull()) {
            draws++;
            gameOver = true;
            statusLabel.setText("Draw! Click to play again. X:" + xWins + " O:" + oWins + " D:" + draws);
            setTitle("Tic-Tac-Toe Game - Draw");
        } else {
            currentPlayer = (currentPlayer.getSymbol() == 'X') ? new OStone() : new XStone();
            statusLabel.setText("Player " + currentPlayer.getSymbol() + "'s turn");
            setTitle("Tic-Tac-Toe Game - Player " + currentPlayer.getSymbol());
        }
    }

    private void placeMark(int row, int col) {
        Stone stone = currentPlayer;
        stones[row][col] = stone;
        JLabel label = cells[row][col];
        label.setText(stone.getDisplayText());
        label.setForeground(stone.getColor());
    }

    private boolean checkWin() {
        char p = currentPlayer.getSymbol();
        for (int i = 0; i < 3; i++) {
            if (stones[i][0].getSymbol() == p && stones[i][1].getSymbol() == p && stones[i][2].getSymbol() == p) {
                winLine = new int[][]{{i, 0}, {i, 1}, {i, 2}};
                return true;
            }
            if (stones[0][i].getSymbol() == p && stones[1][i].getSymbol() == p && stones[2][i].getSymbol() == p) {
                winLine = new int[][]{{0, i}, {1, i}, {2, i}};
                return true;
            }
        }
        if (stones[0][0].getSymbol() == p && stones[1][1].getSymbol() == p && stones[2][2].getSymbol() == p) {
            winLine = new int[][]{{0, 0}, {1, 1}, {2, 2}};
            return true;
        }
        if (stones[0][2].getSymbol() == p && stones[1][1].getSymbol() == p && stones[2][0].getSymbol() == p) {
            winLine = new int[][]{{0, 2}, {1, 1}, {2, 0}};
            return true;
        }
        winLine = null;
        return false;
    }

    private void resetBoard() {
        winAnimationGeneration++;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                stones[r][c] = new EmptyStone();
                cells[r][c].setText("");
            }
        }
        currentPlayer = new XStone();
        gameOver = false;
        statusLabel.setText("Player X's turn. X:" + xWins + " O:" + oWins + " D:" + draws);
        setTitle("Tic-Tac-Toe Game");
    }

    private boolean isBoardFull() {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (stones[r][c].isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    public void newGame() {
        resetBoard();
    }

    public static void main(String[] args) {
        java.awt.EventQueue.invokeLater(TicTacToe::new);
    }

    private Stone[][] stones = new Stone[3][3];
    private int xWins = 0;
    private int oWins = 0;
    private int draws = 0;
    private boolean gameOver = false;
    /**
     * Returns detailed game statistics
     */
    public String getGameStats() {
        int totalGames = xWins + oWins + draws;
        double xWinRate = totalGames > 0 ? (double) xWins / totalGames * 100 : 0.0;
        double oWinRate = totalGames > 0 ? (double) oWins / totalGames * 100 : 0.0;
        double drawRate = totalGames > 0 ? (double) draws / totalGames * 100 : 0.0;
        return String.format("X:" + xWins + " O:" + oWins + " D:" + draws + " (X:%.1f%%, O:%.1f%%, Draw:%.1f%%)", xWinRate, oWinRate, drawRate);
    }
    private int[][] winLine = null;
    private void animateWin() {
        final int[][] line = winLine;
        if (line == null) {
            return;
        }
        final int generation = ++winAnimationGeneration;
        final java.awt.Color highlight = new java.awt.Color(80, 200, 80);
        final java.awt.Color normal = java.awt.Color.WHITE;
        final int flashCount = 6;
        Thread animator = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int p = 0; p < flashCount && winAnimationGeneration == generation; p++) {
                        final boolean on = p % 2 == 0;
                        java.awt.EventQueue.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                for (int[] rc : line) {
                                    cells[rc[0]][rc[1]].setBackground(on ? highlight : normal);
                                }
                            }
                        });
                        Thread.sleep(220);
                    }
                    java.awt.EventQueue.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            for (int[] rc : line) {
                                cells[rc[0]][rc[1]].setBackground(normal);
                            }
                        }
                    });
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        animator.start();
    }
    private int winAnimationGeneration = 0;
}
