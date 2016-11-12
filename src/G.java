// FRAMERATES AT 400x300 RESOLUTION (with no sleep at all)
// 190fps with frustrum ray calcs (with no rendering and no debugging)
// 45fps with lit sphere (no debugging)
// 24fps with lit sphere (debugging)
// 23fps with depth visualised sphere (debugging)
// 26fps with depth visualised cube (no debugging)
// 28fps sphere shadowing cube (no debugging)
// 11fps sphere shadowing cube (debugging)
//  5fps with depth visualised tiled cubes (debugging, view dist = 10, step = 0.1)
//  4fps with shaded/shadowed tiled spheres (debugging, view dist = 10, step = 0.01)
// ...
// 19fps with lit sphere (debugging)
// 15fps voxel sphere (no debugging)
// 12fps voxel sphere (debugging)
// ..
// 13fps voxel tiled spheres (no debugging)
// 11fps voxel tiled spheres (debugging)
//  9fps voxel tiled shadowed spheres (debugging)

import java.applet.Applet;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
//import java.util.Random;
//import java.awt.event.MouseEvent;

public class G extends Applet implements Runnable {

  // CONSTANTS
  
  // for the low rez game jam?
//  private final int viewWidth = 32;
//  private final int viewHeight = 32;
//  private final int viewScaleX = 20;
//  private final int viewScaleY = 20;
  
  private final int viewWidth = 400; //320; //800;
  private final int viewHeight = 300; //200; //600;
  private final int viewScaleX = 2;
  private final int viewScaleY = 2;
 
  private final int[] pixels = new int[viewWidth * viewHeight];

  private final double frameRateSmoothing = 1.0; // number of seconds over which to average the frame rate
  private final long nanosPerSecond = 1000000000;
  // TODO: avoid hogging CPU in final build! Sleep for 0 or 1?
  private final int tickSleepTimeInMs = -1; // milliseconds to sleep after every frame. -1 means no sleep.

  private final double maxViewDist = 100;
  private final double primaryRayStepThreshold = 0.01; // 0.01 = ~25% more steps than 0.1. But 0.1 causes shading bands
  private final double shadowRayStepThreshold = 0.01;  // 0.1 causes self-shadowing
  private final double noIntersection = -1.0;
  // TODO: soft shadows are broken!
  private final double shadowHardness = 128.0; // 2 = soft shadows, 128 = hard shadows

  private final int depthOfRecursivePrimitive = 4;
          
  // VARIABLES
  // 3D voxel grid, with each cell storing distance to nearest solid surface
  private final int numVoxelModels = 1;
  private int voxelRes = 32; // TODO: boundary issues at 256 and greater
  private int halfVoxelRes = voxelRes / 2;
  private byte voxelModels[][][][]; // = new byte[numVoxelModels][voxelRes][voxelRes][voxelRes];
  private boolean isDrawingVoxels = false;

  private int currModel = 6;
  
  private static final boolean keyDown[] = new boolean[65536];
  private static final boolean keyToggle[] = new boolean[65536];
/*  
  private int mouseX;
  private int mouseY;
  private boolean leftMouseDown;
  private boolean rightMouseDown;
*/  

  // per-frame ray stats
  private int numRays;
  private int totalRaySteps;
  private int maxStepsPerRay;
  private int lastRayNumSteps;

  final Vec3 Right = new Vec3(1,0,0);
  final Vec3 Up = new Vec3(0,1,0); // TODO: should this be +1 or -1?
  final Vec3 Forward = new Vec3(0,0,1);
  
  private class Vec3
  {
    double x;
    double y;
    double z;
   
    Vec3(double x, double y, double z)
    {
      this.x = x;
      this.y = y;
      this.z = z;
    }
    
    double length()
    {
      return Math.sqrt(x * x + y * y + z * z);
    }

    double dotProduct(Vec3 v)
    {
      return x * v.x + y * v.y + z * v.z;
    }

    Vec3 add(Vec3 v)
    {
      return new Vec3(x + v.x, y + v.y, z + v.z);
    }

    Vec3 subtract(Vec3 v)
    {
      return new Vec3(x - v.x, y - v.y, z - v.z);
    }
    
    Vec3 multiply(double a)
    {
      return new Vec3(x * a, y * a, z * a);
    }

    Vec3 normalise()
    {
      return this.multiply(1.0 / length());
    }
    
    Vec3 crossProduct(Vec3 v)
    {
      return new Vec3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
    }
  };
  
  private double getDistToSphere(Vec3 pt, double radius)
  {
    return pt.length() - radius;
  }

  private double getDistToBox(Vec3 pt, Vec3 halfSize)
  {
    return new Vec3(Math.max(0, Math.abs(pt.x) - halfSize.x),
                    Math.max(0, Math.abs(pt.y) - halfSize.y),
                    Math.max(0, Math.abs(pt.z) - halfSize.z)).length();
  }

  // TODO: come up with more interesting procedural shapes!
  
  private double getRandomDist(Vec3 pt, double radius)
  {
    // TODO: use Random.setSeed to create a fixed 'random' procedural object?
    return (Math.random() - 0.5) * radius;
  }

  private double getDistTest(Vec3 pt, double size, int recurseDepth)
  {
    //double localDist = pt.length() - radius;
    double localDist = getDistToSphere(pt, size / 2);
    //double localDist = getDistToBox(pt, new Vec3(size / 2, size / 2, size / 2));
            
    double offset = size * 0.5;
    
    if(recurseDepth <= 1)
      return localDist;
    else
      return Math.min(Math.min(localDist,
              getDistTest(pt.add(new Vec3(-offset, offset, 0.0)), size * 0.5, recurseDepth - 1)),
              getDistTest(pt.add(new Vec3(+offset, offset, 0.0)), size * 0.5, recurseDepth - 1));
    
    //return ((pt.x + pt.y + pt.z) - 0.5) * radius;
  }
  
  private Vec3 tilePrimitive(Vec3 pt, double tileSize)
  {
      // tile primitive infinitely along all three axes (within a fixed cell size)
      final double halfTileSize = tileSize / 2.0;
      return new Vec3(pt.x % tileSize - halfTileSize,
                      pt.y % tileSize - halfTileSize,
                      pt.z % tileSize - halfTileSize);
  }
  
  private void createVoxelModels(int newVoxelRes)
  {
    // recreate voxel grid (potentially at a different resolution)
    voxelRes = newVoxelRes;
    halfVoxelRes = voxelRes / 2;
    voxelModels = new byte[numVoxelModels][voxelRes][voxelRes][voxelRes];

    final double tileSize = voxelRes / 4;
    
    for(int model = 0; model < numVoxelModels; model++)
      for(int i = 0; i < voxelRes; i++)
        for(int j = 0; j < voxelRes; j++)
          for(int k = 0; k < voxelRes; k++)
          {
            Vec3 pt;
            double size;
            // tile object within the voxel grid?
            if(keyToggle[KeyEvent.VK_Y])
            {
              pt = tilePrimitive(new Vec3(i, j, k), tileSize);
              size = tileSize / 3;
              //radius = tileSize / 2 - 2;
            }
            else
            {
              pt = new Vec3(i - halfVoxelRes + 0.5, j - halfVoxelRes + 0.5, k - halfVoxelRes + 0.5);
              //radius = halfVoxelRes - 2;
              size = voxelRes / 2;
            }

            // evaluate procedural primitive
            double dist;
            switch(model)
            {
              case 0: dist = getDistTest(pt, size, depthOfRecursivePrimitive); break;
              case 1: dist = getRandomDist(pt, 100); break;
              default: dist = getDistToSphere(pt, size);
            }

            // store into voxel cell the distance to the procedural primitive
            voxelModels[model][i][j][k] = (byte)dist;
          }
  }
  
  private double getDistToVoxelGrid(int modelIndex, Vec3 pt)
  {
    // truncate coordinate to integers, then offset so
    // that (0,0,0) is at the corner of the voxel grid
    int cellX = (int)(pt.x);
    int cellY = (int)(pt.y);
    int cellZ = (int)(pt.z);

    if(keyToggle[KeyEvent.VK_T])
    {
      // modulo only required if tiling voxel grid
      cellX = ((cellX % voxelRes) + voxelRes) % voxelRes;
      cellY = ((cellY % voxelRes) + voxelRes) % voxelRes;
      cellZ = ((cellZ % voxelRes) + voxelRes) % voxelRes;
    }
    else
    {
      cellX = cellX + halfVoxelRes;
      cellY = cellY + halfVoxelRes;
      cellZ = cellZ + halfVoxelRes;
      
      // Find signed distance to boundary of voxel grid (+ve outside, -ve inside)
      // TODO: boundary issues at voxelRes >= 256. distToBoundary used to be floating-point!
      int distToBoundary =
        Math.max(-cellX,
        Math.max(-cellY,
        Math.max(-cellZ,
        Math.max(cellX - voxelRes + 1,
        Math.max(cellY - voxelRes + 1,
                 cellZ - voxelRes + 1)))));

//    double distToBoundary =
//      Math.max(-halfVoxelRes - pt.x,
//      Math.max(-halfVoxelRes - pt.y,
//      Math.max(-halfVoxelRes - pt.z,
//      Math.max(pt.x - halfVoxelRes + 1,
//      Math.max(pt.y - halfVoxelRes + 1,
//               pt.z - halfVoxelRes + 1)))));
    
      if(distToBoundary > 0)
        return Math.max(1, distToBoundary);
    }
    
    // from now on cell coordinate is definitely within the voxel grid
    
    // scaling voxel grid down to unit cube size
//    byte i = (byte)(pt.x * voxelRes);
//    byte j = (byte)(pt.y * voxelRes);
//    byte k = (byte)(pt.z * voxelRes);
    
    return voxelModels[modelIndex][cellX][cellY][cellZ]; //   / (double)voxelRes;
  }

  // TODO: no longer used
//  private double roomTileSize;
  
  private double getDistToRoomGrid(Vec3 pt)
  {
//      roomTileSize = 3;
//      if(keyToggle[KeyEvent.VK_Q])
//        roomTileSize = 1;
//      else if(keyToggle[KeyEvent.VK_W])
//        roomTileSize = 2;
    
      // tile primitive infinitely along all three axes (withing a fixed cell size)
      //Vec3 tiledPt = tilePrimitive(pt, tileSize);
      // tile primitive infinitely along all three axes (within a fixed cell size)
      //final double halfTileSize = roomTileSize / 2.0;

      
      int roomX = (int)(pt.x) / 2;
      int roomZ = (int)(pt.z) / 2;
      // TODO: the abs will probably mirror objects!
      // TODO: probably faster without abs - just avoid rendering negative space!
      double intraRoomX = Math.abs(pt.x % 2) - 1;
      double intraRoomZ = Math.abs(pt.z % 2) - 1;
      
      Vec3 tiledPt = new Vec3(intraRoomX, pt.y, intraRoomZ);
      
//      Vec3 tiledPt = new Vec3(pt.x % roomTileSize, // - halfTileSize,
//                      pt.y,
//                      pt.z % roomTileSize); // - halfTileSize);
      
      // TODO: tile primitives into cells, but allow for a different primitive in each cell!

      // TODO: fill rooms with voxel models rather than procedural models!

      // TODO: scale up space for voxel grid?
//      return getDistToVoxelGrid(0, tiledPt);
      
      if(roomX % 3 == 0)
        return getDistToBox(tiledPt, new Vec3(0.5, 0.5, 0.5));
      else if(roomZ % 3 == 0)
        return getDistTest(tiledPt, 1, depthOfRecursivePrimitive);
      else
        return getDistToSphere(tiledPt, 1);
      
      // TODO: needs adjustment for voxel grid scale
      // return getDistToVoxelGrid(pt);
  }

  private Vec3 getRoomGridNormal(Vec3 pt)
  {
      // TODO: the abs will probably mirror objects!
      // TODO: probably faster without abs - just avoid rendering negative space!
      double intraRoomX = Math.abs(pt.x % 2) - 1;
      double intraRoomZ = Math.abs(pt.z % 2) - 1;
      Vec3 tiledPt = new Vec3(intraRoomX, pt.y, intraRoomZ);
    
      // tile primitive infinitely along all three axes (withing a fixed cell size)
//      Vec3 tiledPt = tilePrimitive(pt, roomTileSize);
//      tiledPt.y = pt.y;
      
      return getSphereNormal(tiledPt);
  }
  
  // returns a normalised normal
  private Vec3 getSphereNormal(Vec3 pt)
  {
    // TODO: normalise not required for unit sphere!
    return pt.normalise();
  }

  // returns a normalised normal
  private Vec3 getBoxNormal(Vec3 pt)
  {
    // TODO
    return null;
  }

  // get distance from a point to the nearest surface point in the world
  private double worldGetDist(Vec3 pt)
  {
    if(keyToggle[KeyEvent.VK_T] && currModel != 1)
    {
      // tile primitive infinitely along all three axes (withing a fixed cell size)
      final double tileSize = 3.0;
      pt = tilePrimitive(pt, tileSize);
              
  /*  
      // TODO: broken - only quarter of sphere is sort of visible!
      pt = new Vec3(Math.IEEEremainder(pt.x, tileSize) - halfTileSize,
                    Math.IEEEremainder(pt.y, tileSize) - halfTileSize,
                    Math.IEEEremainder(pt.z, tileSize) - halfTileSize);
  */
    }
    
    switch (currModel)
    {
      case 1: return getDistToVoxelGrid(0, pt);
      case 2: return getDistToSphere(pt, 1);
      case 3: return getDistToBox(pt, new Vec3(0.5, 0.5, 0.5));
      case 4: return Math.min(getDistToSphere(pt, 1),
                getDistToBox(pt.add(new Vec3(-2, 0, 0)), new Vec3(0.5, 0.5, 0.5)));
      case 5: return getDistTest(pt, 1, depthOfRecursivePrimitive);
      case 6: return getDistToRoomGrid(pt);
      case 7: return getRandomDist(pt, 10 );
      default: return maxViewDist * 10.0;
    }
  }

  // returns a normalised normal
  private Vec3 worldGetNormal(Vec3 pt /* , float objectID */)
  {
    if(isDrawingVoxels)
    {
      // rendering voxel grid
      pt = pt.multiply(halfVoxelRes);
    }
    
    if(keyToggle[KeyEvent.VK_T] && currModel != 1)
    {
      // tile primitive infinitely along all three axes (withing a fixed cell size)
      final double tileSize = 3.0;
      pt = tilePrimitive(pt, tileSize);
      
/*      
      final double tileSize = 3.0;
      final double halfTileSize = tileSize / 2.0;
      pt = new Vec3(pt.x % tileSize - halfTileSize,
                    pt.y % tileSize - halfTileSize,
                    pt.z % tileSize - halfTileSize);
*/      
    }
    
    // TODO: need to calc normal for correct object, i.e. sphere vs box
    if(currModel == 6)
      return getRoomGridNormal(pt);
    else
      return getSphereNormal(pt);
  }
  
  // rayDir must be normalised.
  // maxDist is the maximum distance that the ray will be allowed to travel.
  // returns distance to intersection point,
  // or the 'noIntersection' constant if no intersection found
  private double worldIntersect(Vec3 rayStart, Vec3 rayDir, double maxDist, double rayStepThreshold)
  {
    if(isDrawingVoxels)
    {
      // rendering voxel grid, so expand coordinates from unit cube to voxel grid
      rayStart = rayStart.multiply(halfVoxelRes);
      maxDist *= halfVoxelRes;
      rayStepThreshold *= halfVoxelRes;
    }

    Vec3 pt = rayStart;
    double rayLen = 0.0;
    
    double dist;
    int numSteps = 0;
    while((dist = worldGetDist(pt)) > rayStepThreshold)
    {
      rayLen += dist;
      if(rayLen > maxDist)
      {
        rayLen = noIntersection;
        break;
      }
      
      numSteps++;
      pt = pt.add(rayDir.multiply(dist));
    }
    
    // update global ray stats
    numRays++;
    totalRaySteps += numSteps;
    maxStepsPerRay = Math.max(maxStepsPerRay, numSteps);
    lastRayNumSteps = numSteps;

    if(isDrawingVoxels)
    {
      // rendering voxel grid, so shrink coordinates from voxel grid to unit cube
      if(rayLen != noIntersection)
        rayLen /= halfVoxelRes;
    }
    
    return rayLen;
  }

  // rayDir must be normalised.
  // maxDist is the maximum distance that the ray will be allowed to travel.
  // returns amount of shadow cast,
  // or the 'noIntersection' constant if no shadow is cast
  private double softShadowIntersect(Vec3 rayStart, Vec3 rayDir, double maxDist, double rayStepThreshold)
  {
    if(isDrawingVoxels)
    {
      // rendering voxel grid, so expand coordinates from unit cube to voxel grid
      rayStart = rayStart.multiply(halfVoxelRes);
      maxDist *= halfVoxelRes;
      rayStepThreshold *= halfVoxelRes;
    }
    
    Vec3 pt = rayStart;
    double shadowFactor = 1.0;
    double rayLen = 0.0;
    
    double dist;
    int numSteps = 0;
    while((dist = worldGetDist(pt)) > rayStepThreshold)
    {
      rayLen += dist;
      shadowFactor = Math.min(shadowFactor, shadowHardness * dist / rayLen);
        
      if(rayLen > maxDist)
      {
        shadowFactor = noIntersection;
        break;
      }
      
      numSteps++;
      pt = pt.add(rayDir.multiply(dist));
    }
    
    // update global ray stats
    numRays++;
    totalRaySteps += numSteps;
    maxStepsPerRay = Math.max(maxStepsPerRay, numSteps);
    lastRayNumSteps = numSteps;

    return shadowFactor;
  }
  
  private void drawPixel(int x, int y, int color)
  {
    if(x < 0 || x >= viewWidth || y < 0 || y >= viewHeight)
      return;
    
    pixels[y * viewWidth + x] = color;
  }

  private Thread thread;
  
  @Override
  public void start() {
    enableEvents(AWTEvent.KEY_EVENT_MASK);
//    enableEvents(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);

    thread = new Thread(this);
    thread.start();
  }

  @Override
  public void stop() {
    thread.stop();
  }
  
  @Override
  public void run() {

    // VARIABLES
    int numFrames = 0;
    int fps = 0;
    long lastFpsTime = 0;
    //long lastFpsTime = System.nanoTime(); // TODO: is this actually smaller size?

    // TODO: for AppletViewer, remove later.
    setSize(viewWidth * viewScaleX, viewHeight * viewScaleY);

    // Set up the graphics stuff, double-buffering.
    BufferedImage screen = new BufferedImage(viewWidth, viewHeight, BufferedImage.TYPE_INT_RGB);
    Graphics gfx = screen.getGraphics();
    Graphics appletGraphics = getGraphics();

    // display experimental distance field by default
//    keyToggle[KeyEvent.VK_5] = true;

    // display experimental 'room' distance field by default
//    keyToggle[KeyEvent.VK_6] = true;
    
/*    
    // display tiled voxel grid containing tiled objects by default
    keyToggle[KeyEvent.VK_1] = true;
    keyToggle[KeyEvent.VK_T] = true;
    keyToggle[KeyEvent.VK_Y] = true;
*/
    
    // create the grid of voxels for all voxel models
    createVoxelModels(voxelRes);
    
    // Game loop
    while(true)
    {
      double currTime = (double)System.nanoTime() / nanosPerSecond;

      if(keyDown[KeyEvent.VK_UP])
      {
        keyDown[KeyEvent.VK_UP] = false;
        if(voxelRes < 128)
          createVoxelModels(voxelRes * 2); // recreate voxel models
      }
      
      if(keyDown[KeyEvent.VK_DOWN])
      {
        keyDown[KeyEvent.VK_DOWN] = false;
        if(voxelRes > 4)
          createVoxelModels(voxelRes / 2); // recreate voxel models
      }

/*      
      if(currTime - lastStepTime > stepNanoTime)
      {
        // One turn / step
        steps++;
        lastStepTime = currTime;
        
        // TODO
      }
*/
      
      // Draw background
      //gfx.setColor(Color.yellow);
      //gfx.fillRect(0, 0, viewWidth, viewHeight);

      // Animate or freeze camera?
      double animationTime = currTime; //   / 5;
      if(keyToggle[KeyEvent.VK_F])
        animationTime = Math.PI;
      
//      Vec3 cameraPos = new Vec3(Math.cos(animationTime), 0, Math.sin(animationTime)); // circle around object
//      cameraPos = cameraPos.multiply(1.6); // .multiply(10.0); // .add(new Vec3(5, 5, 0));
//      cameraPos.y = -0.5;
//      Vec3 cameraPos = new Vec3(Math.cos(animationTime), Math.sin(animationTime * 2), 0); // vertical figure-of-8 in front of object
//      final Vec3 cameraLookAt = new Vec3(0, -0.5, 0);
//      Vec3 cameraUp = Up;
      
      // move in straight line
      Vec3 moveDir = new Vec3(1.5, 0, 0.8);
      Vec3 cameraPos = moveDir.multiply(animationTime);
      final Vec3 cameraLookAt = cameraPos.add(moveDir).add(new Vec3(0,-0.2,0));
      cameraPos = cameraPos.add(new Vec3(0,-1.5,0));
      Vec3 cameraUp = Up;
      
      // Ensure that camera basis vectors are all mutually perpendicular
      final Vec3 cameraForward = cameraLookAt.subtract(cameraPos).normalise();
      final Vec3 cameraRight = cameraForward.crossProduct(cameraUp);
      cameraUp = cameraRight.crossProduct(cameraForward);

      // Decide if we are rendering voxels or not. Scale is different.
      isDrawingVoxels = (currModel == 1 /* || currModel == 6 */ );
    
      // Draw to every pixel in the view
      //int pixelIndex = 0;
      for(int row = 0; row < viewHeight; row++)
      {
        for(int col = 0; col < viewWidth; col++)
        {
          // 45 degree horizontal Field Of View (vertical FOV is proportional)
          double xFrac = (double)(col - viewWidth * 0.5) / viewWidth;
          double yFrac = (double)(row - viewHeight * 0.5) / viewWidth;
          double zFrac = 0.5;
          
          // fire a ray from the camera into the world
          Vec3 rayStart = cameraPos;
          Vec3 rayDir = cameraRight.multiply(xFrac).add(cameraUp.multiply(yFrac)).add(cameraForward.multiply(zFrac));
          rayDir = rayDir.normalise();
          double depth = worldIntersect(rayStart, rayDir, maxViewDist, primaryRayStepThreshold);


          if(keyToggle[KeyEvent.VK_R])
          {
            // visualise number of steps for each ray
            // 1 step = red, 8 steps = green, 16 steps = blue, > 24 steps = black
            drawPixel(col, row, 0xff0000 >> (lastRayNumSteps - 1));
            continue;
          }
          
          if(keyToggle[KeyEvent.VK_D])
          {
            // visualise depth to each ray intersection
            final double invMaxViewDist = 1.0 / maxViewDist;
            byte depthByte = 0;
            if(depth != noIntersection)
              depthByte = (byte)((1.0 - depth * invMaxViewDist) * 255.0);
            drawPixel(col, row, (depthByte << 16) + (depthByte << 8) + depthByte);
            continue;
          }

          // render shaded (and optionally shadowed) pixel
          if(depth == noIntersection)
          {
            drawPixel(col, row, 0x0000ff);
          }
          else
          {
            final Vec3 dirToLight = new Vec3(-1, -0.75, -0.5).normalise(); // must be normalised
            double shadowFactor = noIntersection;
            if(keyToggle[KeyEvent.VK_S])
            {
              // fire a shadow ray
              Vec3 surfacePt = rayStart.add(rayDir.multiply(depth));
              // start shadow ray away from surface to avoid self-shadowing
              Vec3 shadowRayStart = surfacePt.add(dirToLight.multiply(0.05 + shadowRayStepThreshold));

              //double shadowDist = worldIntersect(shadowRayStart, dirToLight, maxViewDist, shadowRayStepThreshold);
              shadowFactor = softShadowIntersect(shadowRayStart, dirToLight, maxViewDist, shadowRayStepThreshold);
            }
            
            // calc lighting at point
            // TODO: this requires correct normals on all objects
            Vec3 pt = rayStart.add(rayDir.multiply(depth));
            Vec3 normal = worldGetNormal(pt);
            //Vec3 dirToLight = new Vec3(0, 0, -1); // must be normalised
            //lightDir = lightDir.normalise();
            //normal = normal.normalise();

            if(shadowFactor == noIntersection)
              // shadow ray did not hit another surface,
              // so this surface point is not in shadow at all
              shadowFactor = 1.0;
            else
              // shadow ray hit or passed close to another surface,
              // so this surface point is in partial or full shadow
              shadowFactor = 1.0 - shadowFactor;

            double normalisedIntensity = Math.min(Math.max(0.0, normal.dotProduct(dirToLight)) * shadowFactor, 1.0);
            int intensity = (int)(normalisedIntensity * 255.0);
            drawPixel(col, row, (intensity << 16) + (intensity << 8) + intensity);
          }
        }
      }

      // Copy buffer of pixels to the BufferedImage
      screen.setRGB(0, 0, viewWidth, viewHeight, pixels, 0, viewWidth);

      // Draw status text
      gfx.setColor(Color.RED);
      gfx.drawString(
              String.valueOf(fps) + "fps " +
              voxelRes + "vox " + 
              numRays + "rays " + 
              "Steps: avg " + totalRaySteps / Math.max(1, numRays) +
              ", max " + maxStepsPerRay +
              ", total " + totalRaySteps,
              0, viewHeight);
      
      // Reset per-frame ray stats
      numRays = 0;
      totalRaySteps = 0;
      maxStepsPerRay = 0;

      // Copy the entire rendered image onto the screen
      //appletGraphics.drawImage(screen, 0, 0, null);
      appletGraphics.drawImage(screen,
              0, 0, viewWidth * viewScaleX, viewHeight * viewScaleY,
              0, 0, viewWidth, viewHeight, null);

      if(tickSleepTimeInMs != -1)
      {
        // Lock frame rate
        try
        {
          Thread.sleep(tickSleepTimeInMs);
        }
        catch (Exception e)
        {
        };
      }
      
      // Measure frame rate
      numFrames++;
      //if(frames % frameRateSmoothing == 0)
      double delta = (double)(System.nanoTime() - lastFpsTime) / nanosPerSecond;
      if(delta > frameRateSmoothing)
      {
        fps = (int)(numFrames / delta);
        numFrames = 0;
        lastFpsTime = System.nanoTime();
      }

      // Should applet quit?
      if (!isActive()) {
        return;
      }
    }
  }

/*  
  public void processMouseEvent(MouseEvent e)
  {
    mouseX = e.getX();
    mouseY = e.getY();
    //mouseDown = (e.getButton() == MouseEvent.BUTTON1);
    leftMouseDown = ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0);
    rightMouseDown = ((e.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) != 0);
  }
*/
  
  @Override
  public void processKeyEvent(KeyEvent e)
  {
    keyDown[e.getKeyCode()] = (e.getID() == KeyEvent.KEY_PRESSED);
      
    if(e.getID() == KeyEvent.KEY_PRESSED)
      keyToggle[e.getKeyCode()] = !keyToggle[e.getKeyCode()];

    int numericKey = e.getKeyCode() - KeyEvent.VK_1 + 1;
    if(numericKey >= 1 && numericKey <= 7)
      currModel = numericKey;

/*    
    switch(e.getKeyCode())
    {
      case KeyEvent.VK_UP:
        createVoxelModels(voxelRes * 2);
        break;
        
      case KeyEvent.VK_DOWN:
        createVoxelModels(voxelRes / 2);
        break;
    }
*/    
  }

/*  
  @Override
  public boolean handleEvent(Event e) {
    keys[e.key] = (e.id == KeyEvent.KEY_PRESSED);

    switch (e.id) {
          case Event.KEY_PRESS:
          case Event.KEY_ACTION:
              // key pressed
              break;
          case Event.KEY_RELEASE:
              // key released
              break;
          case Event.MOUSE_DOWN:
              // mouse button pressed
              //mouseDown = true;
              break;
          case Event.MOUSE_UP:
              // mouse button released
              //mouseDown = false;
              break;
          case Event.MOUSE_MOVE:
              //mouseX = (MouseEvent)e;
              break;
          case Event.MOUSE_DRAG:
              break;
     }
     return false;
  }
*/  
}