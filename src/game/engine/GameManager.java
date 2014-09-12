package game.engine;


import game.MenuNavigator;
import game.engine.characters.Monster;
import game.engine.characters.Projectile;
import game.engine.characters.Tower;
import javafx.animation.AnimationTimer;
import javafx.animation.PathTransition;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Iterator;


//class used to delegate between game classes after game has been initialized
/*  class is used when user starts or loads a new game from the game.MainMenuController
    responsible for generating map array and parsing the array to paint the map onto
    stack pane

    starts threads for:
        spawning monster
        tower attack
 */
public class GameManager {
    private  TileMap gameMap;                      //updating tiles upon buying tower
    private  Group monsterLayer;                   //add and remove monsters from the map
    private  GameState game;                       //access game data
    private  Scene gameScene;                      //used by controller
    private  ArrayList<Monster> monsterRemovalQueue;
    private  ArrayList<Projectile> animationQueue;
    private  GameController gameController;
    private  AnimationTimer gameLoop;

    //Exception thrown in fxml file not found
    public void initialize() throws java.io.IOException{
        //initialize globals
        game = GameState.getNewGame();
        gameMap = new TileMap(1280 ,800);
        monsterLayer = new Group();

        //creates gui hierarchy
        FXMLLoader loader = new FXMLLoader(MenuNavigator.GAMEUI);
        StackPane gamePane = new StackPane();
        Group tilemapGroup = new Group();
        monsterLayer.getChildren().add(tilemapGroup);
        tilemapGroup.getChildren().add(gameMap);
        gamePane.getChildren().add(monsterLayer);

        //opens stream to get controller reference
        Node gameUI = (Node)loader.load(MenuNavigator.GAMEUI.openStream());
        gamePane.getChildren().add(gameUI);
        gameScene = new Scene(gamePane);
        gameScene.getStylesheets().add(GameManager.class.getResource("res/menu/gamestyle.css").toExternalForm());
        gameController = loader.<GameController>getController();
        gameController.setGameManager(this);

        MenuNavigator.stage.setScene(gameScene);
        Monster.setPath(gameMap.getPath());
        monsterRemovalQueue = new ArrayList<Monster>();
        animationQueue = new ArrayList<Projectile>();
        startGameLoop();
    }

    public  Scene getGameScene(){
        return gameScene;
    }



    /*verifies the node is open and the user has resources
      called by GameController when user clicks buyTower button
    */
    public void buyTower(double xCords , double yCords){
        //converts the mouse click coordinates to tile format
        int xTile = (int)(xCords / 64);
        int yTile = (int)(yCords / 64);

        //node and resource check before tile update
        if(gameMap.nodeOpen(xTile,yTile)){
            if(game.getResources() > 49) {
                game.addTower(new Tower(xTile, yTile));
                game.setResources(game.getResources() - 50);
                gameMap.setMapNode(((int) (xCords / 64)), ((int) (yCords / 64)), 7);
            }//end if - has resources
        }//end if - is node open
    }//end method buyTower



    private void createMonster(int health){
        game.getMonstersAlive().add(new Monster(health));
        monsterLayer.getChildren().add(game.getMonstersAlive().get(game.getMonstersAlive().size() - 1).getView());
    }//end method - createMonster

    /*
        Updates each monsters location along the path
     */
    private void updateLocations(int timestamp){
        if(!game.getMonstersAlive().isEmpty()){
            for (Monster monster : game.getMonstersAlive()) {
                monster.updateLocation(1);
            }//end for checked monsters
        }//end if- monsters populated
    }//end method - update locations

    //gets projectile data from Tower and transfers it to Manager for gui actions
    private void getProjectiles(){
        for(Tower tower : game.getPlayerTowers()){
            for(Projectile projectile : tower.getProjectileList()){
                animationQueue.add(projectile);
                monsterLayer.getChildren().add(projectile);
            }
            tower.getProjectileList().clear();
        }

    }

    //updates the projectiles location from the tower to the monster
    private void updateProjectiles(){
        for(Projectile projectile : animationQueue){
            projectile.updatePath();
        }
    }

    //updates FXML labels
    private void updateLabels(int timer){
        //labels must be updated through controller or reference must be passed in initialize
            gameController.updateLabels(
                Integer.toString(game.getLevel()) ,
                Integer.toString(game.getLives()) ,
                Integer.toString(game.getResources()) ,
                Integer.toString(game.getScore()) ,
                Integer.toString(timer)
        );
    }


    /*
        Method is called when the game is quit/loss
        to display results and prepare to return to menu or
        create a new game.
     */
    public void stopGame(){
        pauseGame();
        game.setState(GameState.IS_STOPPED);
        gameLoop.stop();
    }

    /*
        Method is called when the game is paused to control
        background threads.
     */
    public void pauseGame(){
        game.setState(GameState.IS_PAUSED);
        for(Tower tower : game.getPlayerTowers()){
            tower.getTowerAttacker().cancel();
        }
    }
    /*
        Method is called when game is running to control
        background threads.
     */
    public void resumeGame(){
        game.setState(GameState.IS_RUNNING);
        for(Tower tower : game.getPlayerTowers()){
            tower.getTowerAttacker().start();
        }
    }


    /*
        Checks monsters for killSwitch than removes them and
        clears the deletion queue. Rewards or punishes player
        if the path was finished.
     */
    private synchronized void removeMonsters(){
        for (Monster monster : game.getMonstersAlive()){
            if (monster.killSwitch){
                monsterRemovalQueue.add(monster);
                if (monster.pathFinished){
                    game.setLives((game.getLives()) - 1);
                }// end if - monster finished path/remove life
                else{
                    game.setResources((game.getResources()) + monster.getReward());
                    game.setScore(game.getScore() + (monster.getReward() * game.getLevel()));
                }//end else - monster slain/ give reward
            }//end if - dead monster
        }//end for - add monster to removal queue
        for (Monster monster : monsterRemovalQueue) {
            if (monster.killSwitch) {
                monster.getView().setVisible(false);
                game.getMonstersAlive().remove(monster);
            }
        }
        monsterRemovalQueue.clear();
    }

    private void startGameLoop() {
        final LongProperty lastUpdateTime = new SimpleLongProperty(0);
        final AnimationTimer timer = new AnimationTimer() {
            int timer = 10;

            @Override
            public void handle(long timestamp) {

                if (timestamp/ 1000000000 != lastUpdateTime.get()) {
                    timer--;
                    if(timer > 19) {
                        createMonster(3);
                    }
                    else if(timer <= 0){
                        game.setLevel(game.getLevel() + 1);
                        timer = 30;
                    }//end if - 30 second wave timer
                }//end if - second passed
                removeMonsters();
                updateLocations((1));
                getProjectiles();
                updateProjectiles();
                lastUpdateTime.set(timestamp / 1000000000);
                updateLabels(timer);
            }//end handle

        };
        gameLoop = timer;
        timer.start();
    }

}//end class GameManager
