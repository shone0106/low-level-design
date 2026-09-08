/*
 * ============================================================
 * REQUIREMENTS
 * ============================================================
 *
 * 1. Two players play by taking turns.
 * 2. Each player has a symbol: X or O.
 * 3. A player can place their symbol on an empty cell.
 * 4. Invalid moves should be rejected.
 * 5. A player wins if they complete a row, column, or diagonal.
 * 6. If the board is full without a winner, the game is a draw.
 * 7. A new game can be started after the previous game ends.
 *
 *
 * ============================================================
 * CORE ENTITIES
 * ============================================================
 *
 * Player
 * Position
 * Move
 * Board
 * Game
 *
 *
 * ============================================================
 * KEY DESIGN DECISIONS
 * ============================================================
 *
 * 1. Move represents "who played where".
 * 2. Board owns board-level logic.
 * 3. Game owns game-level state and flow.
 * 4. A new game is a new Game object.(as starting a new game required to throw away prev state like board, player etc. so essentialy creating a new game is better)
 */


package org.lld;

import java.util.Arrays;


// Symbol is a fixed domain value, so use enum instead of String.
enum Symbol {
    X,
    O
}


// Explicit states make the game lifecycle easy to understand.
enum GameStatus {
    IN_PROGRESS,
    PLAYER_WON,
    DRAW
}


class Player {

    private final String name;
    private final Symbol symbol;

    Player(String name, Symbol symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public Symbol getSymbol() {
        return symbol;
    }
}


// Encapsulates the location of a move on the board.
class Position {

    private final int row;
    private final int col;

    Position(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }
}


// Represents one player action.
class Move {

    private final Player player;
    private final Position position;

    Move(Player player, Position position) {
        this.player = player;
        this.position = position;
    }

    public Player getPlayer() {
        return player;
    }

    public Position getPosition() {
        return position;
    }
}


class Board {

    private final int size;

    // No Cell class needed; each cell only needs a Symbol.
    private final Symbol[][] board;


    Board(int size) {

        if (size <= 0) {
            throw new IllegalArgumentException(
                    "Board size must be positive"
            );
        }

        this.size = size;
        this.board = new Symbol[size][size];
    }


    // Board owns position/cell validation.
    public void placeSymbol(Position position, Symbol symbol) {

        validatePosition(position);

        if (!isEmpty(position)) {
            throw new IllegalArgumentException(
                    "Position is already occupied"
            );
        }

        board[position.getRow()][position.getCol()] = symbol;
    }


    public boolean isEmpty(Position position) {

        validatePosition(position);

        return board[position.getRow()][position.getCol()] == null;
    }


    public boolean isFull() {

        for (int row = 0; row < size; row++) {

            for (int col = 0; col < size; col++) {

                if (board[row][col] == null) {
                    return false;
                }
            }
        }

        return true;
    }


    // Board knows its own structure, so winner detection belongs here.
    public Symbol getWinner() {

        // Check rows
        for (int row = 0; row < size; row++) {

            Symbol symbol = board[row][0];

            if (symbol == null) {
                continue;
            }

            boolean winner = true;

            for (int col = 1; col < size; col++) {

                if (board[row][col] != symbol) {
                    winner = false;
                    break;
                }
            }

            if (winner) {
                return symbol;
            }
        }


        // Check columns
        for (int col = 0; col < size; col++) {

            Symbol symbol = board[0][col];

            if (symbol == null) {
                continue;
            }

            boolean winner = true;

            for (int row = 1; row < size; row++) {

                if (board[row][col] != symbol) {
                    winner = false;
                    break;
                }
            }

            if (winner) {
                return symbol;
            }
        }


        // Check main diagonal
        Symbol symbol = board[0][0];

        if (symbol != null) {

            boolean winner = true;

            for (int i = 1; i < size; i++) {

                if (board[i][i] != symbol) {
                    winner = false;
                    break;
                }
            }

            if (winner) {
                return symbol;
            }
        }


        // Check anti-diagonal
        symbol = board[0][size - 1];

        if (symbol != null) {

            boolean winner = true;

            for (int i = 1; i < size; i++) {

                if (board[i][size - 1 - i] != symbol) {
                    winner = false;
                    break;
                }
            }

            if (winner) {
                return symbol;
            }
        }

        return null;
    }


    private void validatePosition(Position position) {

        if (position == null ||
                position.getRow() < 0 ||
                position.getRow() >= size ||
                position.getCol() < 0 ||
                position.getCol() >= size) {

            throw new IllegalArgumentException(
                    "Invalid position"
            );
        }
    }


    public void printBoard() {

        for (Symbol[] row : board) {
            System.out.println(Arrays.toString(row));
        }
    }
}


class Game {

    private final Player player1;
    private final Player player2;
    private final Board board;

    private Player currentPlayer;
    private Player winner;
    private GameStatus status;


    Game(Player player1, Player player2, int boardSize) {

        this.player1 = player1;
        this.player2 = player2;
        this.board = new Board(boardSize);

        // Player 1 starts.
        this.currentPlayer = player1;
        this.status = GameStatus.IN_PROGRESS;
    }


    /*
     * Game owns game-level rules:
     * - Is game active?
     * - Is it this player's turn?
     * - Did someone win?
     * - Is it a draw?
     * - Whose turn is next?
     */
    public void makeMove(Move move) {

        if (status != GameStatus.IN_PROGRESS) {

            throw new IllegalStateException(
                    "Game has already ended"
            );
        }


        // Game validates player/turn.
        if (move == null || move.getPlayer() != currentPlayer) {

            throw new IllegalArgumentException(
                    "Invalid player. It is not your turn"
            );
        }


        // Board validates position and occupancy.
        board.placeSymbol(
                move.getPosition(),
                move.getPlayer().getSymbol()
        );


        // Check winner after every move.
        Symbol winningSymbol = board.getWinner();

        if (winningSymbol != null) {

            winner = currentPlayer;
            status = GameStatus.PLAYER_WON;

            return;
        }


        // No winner + board full = draw.
        if (board.isFull()) {

            status = GameStatus.DRAW;

            return;
        }


        // Game continues.
        switchTurn();
    }


    private void switchTurn() {

        currentPlayer =
                currentPlayer == player1
                        ? player2
                        : player1;
    }


    public GameStatus getStatus() {
        return status;
    }


    public Player getCurrentPlayer() {
        return currentPlayer;
    }


    public Player getWinner() {
        return winner;
    }


    public Board getBoard() {
        return board;
    }
}


public class TicTacToe {

    public static void main(String[] args) {

        Player player1 =
                new Player("Alice", Symbol.X);

        Player player2 =
                new Player("Bob", Symbol.O);


        // Each Game object represents one game.
        Game game =
                new Game(player1, player2, 3);


        game.makeMove(
                new Move(
                        player1,
                        new Position(0, 0)
                )
        );

        game.makeMove(
                new Move(
                        player2,
                        new Position(1, 0)
                )
        );

        game.makeMove(
                new Move(
                        player1,
                        new Position(0, 1)
                )
        );

        game.makeMove(
                new Move(
                        player2,
                        new Position(1, 1)
                )
        );

        game.makeMove(
                new Move(
                        player1,
                        new Position(0, 2)
                )
        );


        System.out.println(game.getStatus());
        // PLAYER_WON

        System.out.println(
                game.getWinner().getName()
        );
        // Alice


        // New game = new Game object.
        Game newGame =
                new Game(player1, player2, 3);

        System.out.println(newGame.getStatus());
        // IN_PROGRESS
    }
}


/*
 * ============================================================
 * QUICK REVISION
 * ============================================================
 *
 * IMPORTANT:
 *   Game = game-level rules
 *   Board = board-level rules
 *
 * No Cell needed as we don't need to store complex information.
 * Symbol should be enum.
 * New game = new Game object.
 */
