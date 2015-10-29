///////////////////////////////////////////////////////////////////////////////
//PROJECT:       Micro-Manager
//SUBSYSTEM:     Display implementation
//-----------------------------------------------------------------------------
//
// AUTHOR:       Chris Weisiger, 2015
//
// COPYRIGHT:    University of California, San Francisco, 2015
//
// LICENSE:      This file is distributed under the BSD license.
//               License text is included with the source distribution.
//
//               This file is distributed in the hope that it will be useful,
//               but WITHOUT ANY WARRANTY; without even the implied warranty
//               of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//
//               IN NO EVENT SHALL THE COPYRIGHT OWNER OR
//               CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
//               INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.

package org.micromanager.display.internal;

import com.google.common.eventbus.Subscribe;

import ij.ImagePlus;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Timer;
import java.util.TimerTask;

import javax.swing.SwingUtilities;

import org.micromanager.data.Coords;
import org.micromanager.data.Datastore;
import org.micromanager.data.Image;

import org.micromanager.display.DisplaySettings;
import org.micromanager.display.DisplayWindow;
import org.micromanager.display.NewDisplaySettingsEvent;
import org.micromanager.display.NewImagePlusEvent;
import org.micromanager.display.internal.events.CanvasDrawCompleteEvent;
import org.micromanager.display.internal.events.DefaultPixelsSetEvent;
import org.micromanager.display.internal.events.HistogramRecalcEvent;
import org.micromanager.display.internal.events.HistogramRequestEvent;
import org.micromanager.display.internal.events.NewHistogramsEvent;

import org.micromanager.internal.utils.ImageUtils;
import org.micromanager.internal.utils.ReportingUtils;

/**
 * This class handles the queue of images that have been requested to be drawn.
 * The queue is necessary so that a large influx of draw requests (e.g. due
 * to a slow computer or a high rate of data acquisition) do not cause the
 * display to become bogged down: we use the queue to effectively reject
 * all but the newest display request. The workflow is as follows:
 * 1) Client code calls DisplayWindow.setDisplayedImageTo() to choose the
 *    displayed image.
 * 2) setDisplayedImageTo() calls CanvasUpdateQueue.enqueue() to add the
 *    image to the queue of images waiting to be displayed.
 * 3) enqueue() adds the image to the queue, and then adds an event to the
 *    Event Dispatch Thread (EDT) to call consumeImages()
 * 4) consumeImages() pulls the most recent image off of the queue (throwing
 *    away all older images in the process), draws it, and posts a
 *    PixelsSetEvent (to allow other code to draw things) and a
 *    CanvasDoneDrawingEvent afterwards (to notify that drawing is complete).
 *    Then, if the queue is not empty, it adds an event to the EDT to draw
 *    itself.
 */
public class CanvasUpdateQueue {

   /**
    * Simple class for tracking our history with respect to calculating
    * histograms for a single channel.
    */
   private static class HistogramHistory {
      ArrayList<HistogramData> datas_ = null;
      long lastUpdateTime_ = 0;
      Timer timer_ = null;
      boolean needsUpdate_ = true;
      int imageHash_ = 0;
      public HistogramHistory() {
         datas_ = new ArrayList<HistogramData>();
      }
   }

   private final Datastore store_;
   private final MMVirtualStack stack_;
   private ImagePlus plus_;
   private final DisplayWindow display_;
   private final Runnable consumer_;

   private final Object drawLock_;
   private final LinkedBlockingQueue<Coords> coordsQueue_;
   private boolean shouldAcceptNewCoords_ = true;
   private HashMap<Integer, HistogramHistory> channelToHistory_;
   // Unfortunately, even though we do all of our work in the EDT, there's
   // no way for us to tell Swing to paint *right now* -- we can only put a
   // draw command on the EDT to be processed later. This boolean allows us
   // to recognize when we're waiting for such a draw to process, so we don't
   // spam up the EDT with lots of excess draw requests.
   private boolean amWaitingForDraw_ = false;
   private boolean shouldReapplyLUTs_;

   public static CanvasUpdateQueue makeQueue(DisplayWindow display,
         MMVirtualStack stack, Object drawLock) {
      CanvasUpdateQueue queue = new CanvasUpdateQueue(display, stack,
            drawLock);
      display.registerForEvents(queue);
      return queue;
   }

   /**
    * The drawLock parameter is a shared object between this class and the
    * DisplayWindow, as the display is not allowed to close when we are in
    * the middle of drawing anything (or equivalently, we are not allowed to
    * draw when the display is closing).
    */
   private CanvasUpdateQueue(DisplayWindow display, MMVirtualStack stack,
         Object drawLock) {
      display_ = display;
      stack_ = stack;
      drawLock_ = drawLock;
      store_ = display_.getDatastore();
      plus_ = display_.getImagePlus();
      coordsQueue_ = new LinkedBlockingQueue<Coords>();
      channelToHistory_ = new HashMap<Integer, HistogramHistory>();
      consumer_ = new Runnable() {
         @Override
         public void run() {
            consumeImages();
         }
      };
   }

   /**
    * Add an image's coords to the queue, and post a request to the EDT to
    * consume the corresponding image.
    * TODO: hypothetically this could jam up the EDT with consume requests,
    * though in practice they ought to fizzle out as soon as they're called
    * if the queue is empty.
    */
   public void enqueue(Coords coords) {
      if (!shouldAcceptNewCoords_) {
         // Additions are currently blocked
         throw new RuntimeException("Attempted to add images to canvas update queue when it is blocked.");
      }
      try {
         coordsQueue_.put(coords);
      }
      catch (InterruptedException e) {
         ReportingUtils.logError("Interrupted while adding coords " + coords + " to queue");
      }
      SwingUtilities.invokeLater(consumer_);
   }

   /**
    * Draw the most recent image on the queue, if any, and then re-call
    * ourselves if there are still more images to draw once that finishes.
    * Only called on the EDT.
    */
   private void consumeImages() {
      Coords coords = null;
      if (amWaitingForDraw_) {
         // No point in running right now as we're waiting for a draw
         // request to make its way through the EDT.
         return;
      }
      // Grab images from the queue until we get the last one, so all
      // others get ignored (because we don't have time to display them).
      while (!coordsQueue_.isEmpty()) {
         coords = coordsQueue_.poll();
      }
      if (coords == null) {
         // No images in the queue; nothing to do.
         return;
      }
      if (plus_.getCanvas() == null) {
         // The display may have gone away while we were waiting.
         return;
      }
      final Image image = store_.getImage(coords);
      if (image == null) {
         // Odd; is this an error situation?
         return;
      }
      showImage(image);
   }

   @Subscribe
   public void onCanvasDrawComplete(CanvasDrawCompleteEvent event) {
      amWaitingForDraw_ = false;
      if (!coordsQueue_.isEmpty()) {
         // New image(s) arrived while we were drawing; repeat.
         SwingUtilities.invokeLater(consumer_);
      }
   }

   /**
    * Show an image -- set the pixels of the canvas and update the display.
    */
   private void showImage(Image image) {
      // This synchronized block corresponds to one in
      // DefaultDisplayWindow.forceClosed(), and ensures that we do not lose
      // the objects needed to perform drawing operations while we are trying
      // to do those operations.
      synchronized(drawLock_) {
         try {
            if (plus_.getProcessor() == null) {
               // Display went away since we last checked.
               return;
            }
            amWaitingForDraw_ = true;
            stack_.setCoords(image.getCoords());
            Object pixels = image.getRawPixels();
            // If we have an RGB byte array, we need to convert it to an
            // int array for ImageJ's consumption.
            if (plus_.getProcessor() instanceof ColorProcessor &&
                  pixels instanceof byte[]) {
               pixels = ImageUtils.convertRGB32BytesToInt(
                     (byte[]) pixels);
            }
            plus_.getProcessor().setPixels(pixels);
            // Recalculate histogram data, if necessary (because the image
            // is different from the last one we've calculated or because we
            // need to force an update).
            int channel = image.getCoords().getChannel();
            if (!channelToHistory_.containsKey(channel)) {
               HistogramHistory history = new HistogramHistory();
               channelToHistory_.put(channel, history);
            }
            HistogramHistory history = channelToHistory_.get(channel);
            if (history.imageHash_ != image.hashCode() ||
                  history.needsUpdate_) {
               scheduleHistogramUpdate(image, history);
            }
            // RGB images need to have their LUTs reapplied, because the
            // image scaling is encoded into the pixel data. And in other
            // situations we also need to just reapply LUTs now.
            if (shouldReapplyLUTs_ ||
                  plus_.getProcessor() instanceof ColorProcessor) {
               if (plus_.getProcessor() instanceof ColorProcessor) {
                  // Create a new snapshot which will be used as a basis for
                  // calculating image stats.
                  ((ColorProcessor) plus_.getProcessor()).snapshot();
               }
               // Must apply LUTs to the display now that it has pixels.
               LUTMaster.initializeDisplay(display_);
               shouldReapplyLUTs_ = false;
            }
            plus_.updateAndDraw();
            display_.postEvent(new DefaultPixelsSetEvent(image, display_));
         }
         catch (Exception e) {
            ReportingUtils.logError(e, "Error drawing image at " + image.getCoords());
         }
      }
   }

   /**
    * Determine whether or not to recalculate histogram data for the provided
    * image. If we do need to calculate the histogram, we may need to delay
    * it until later.
    */
   private void scheduleHistogramUpdate(final Image image,
         final HistogramHistory history) {
      int channel = image.getCoords().getChannel();

      DisplaySettings settings = display_.getDisplaySettings();
      Double updateRate = settings.getHistogramUpdateRate();
      if (updateRate == null) {
         // Assume we always update.
         updateRate = 0.0;
      }
      if (updateRate < 0) {
         // Never update histograms.
         return;
      }
      else if (updateRate > 0 &&
            System.currentTimeMillis() - history.lastUpdateTime_ <= updateRate * 1000) {
         // Calculate histogram sometime in the future. Only if a timer isn't
         // already running for this channel.
         synchronized(history) {
            if (history.timer_ != null) {
               // Already a timer, so don't do anything.
               return;
            }
            history.timer_ = new Timer(
                  "Histogram calculation delay for " + channel);
            TimerTask task = new TimerTask() {
               @Override
               public void run() {
                  updateHistogram(image, history);
               }
            };
            long waitTime = (long) (System.currentTimeMillis() +
               (updateRate * 1000) - history.lastUpdateTime_);
            history.timer_.schedule(task, waitTime);
         }
      }
      else {
         // We either always update, or it's been too long since the last
         // update, so do it now.
         updateHistogram(image, history);
      }
   }

   /**
    * Generate new HistogramDatas for the provided image, and post a
    * NewHistogramsEvent.
    */
   private void updateHistogram(Image image, HistogramHistory history) {
      DisplaySettings settings = display_.getDisplaySettings();
      int channel = image.getCoords().getChannel();
      Double percentage = settings.getExtremaPercentage();
      if (percentage == null) {
         percentage = 0.0;
      }
      // We may need to apply our newly-calculated values to the display
      // contrast settings.
      boolean shouldUpdate = (settings.getShouldAutostretch() != null &&
            settings.getShouldAutostretch());
      Integer[] mins = new Integer[image.getNumComponents()];
      Integer[] maxes = new Integer[image.getNumComponents()];
      Double[] gammas = new Double[image.getNumComponents()];
      synchronized(history) {
         history.datas_.clear();
         for (int i = 0; i < image.getNumComponents(); ++i) {
            // 8 means 256 bins.
            HistogramData data = ContrastCalculator.calculateHistogram(
                  image, i, 8, percentage);
            history.datas_.add(data);
            if (shouldUpdate) {
               mins[i] = data.getMinIgnoringOutliers();
               maxes[i] = data.getMaxIgnoringOutliers();
               gammas[i] = settings.getSafeContrastGamma(channel, i, 1.0);
            }
         }
         history.imageHash_ = image.hashCode();
         history.needsUpdate_ = false;
         history.lastUpdateTime_ = System.currentTimeMillis();
         display_.postEvent(new NewHistogramsEvent(channel, history.datas_));
         if (shouldUpdate) {
            DisplaySettings.DisplaySettingsBuilder builder = settings.copy();
            builder.safeUpdateContrastSettings(
                  new DefaultDisplaySettings.DefaultContrastSettings(
                     mins, maxes, gammas, true),
                  channel);
            display_.setDisplaySettings(builder.build());
         }
         // Allow future jobs to be scheduled.
         history.timer_ = null;
      }
   }

   /**
    * Wait for the coords queue to become empty, and block any additions to
    * it.
    */
   public synchronized void halt() {
      shouldAcceptNewCoords_ = false;
      coordsQueue_.clear();
   }

   /**
    * Allow additions to the coords queue again. Since halt() empties the queue
    * and may potentially result in there being nothing drawn at all, we add a
    * redraw now.
    */
   public void resume() {
      shouldAcceptNewCoords_ = true;
      display_.requestRedraw();
   }

   /**
    * Force the display to reapply LUTs. This is used to deal with certain
    * bizarre situations in which the LUTs are "lost" for unknown reasons,
    * defaulting the display to grayscale.
    */
   public void reapplyLUTs() {
      shouldReapplyLUTs_ = true;
      if (coordsQueue_.isEmpty() && shouldAcceptNewCoords_) {
         // Force refresh of the display.
         enqueue(display_.getDisplayedImages().get(0).getCoords());
      }
   }

   @Subscribe
   public void onNewImagePlus(NewImagePlusEvent event) {
      plus_ = event.getImagePlus();
   }

   @Subscribe
   public void onNewDisplaySettings(NewDisplaySettingsEvent event) {
      // The new settings may have new contrast settings, so reapply contrast.
      reapplyLUTs();
   }

   /**
    * Someone wants us to recalculate histograms.
    */
   @Subscribe
   public void onHistogramRecalc(HistogramRecalcEvent event) {
      channelToHistory_.get(event.getChannel()).needsUpdate_ = true;
   }

   /**
    * Someone is requesting that the current histograms be posted.
    */
   @Subscribe
   public void onHistogramRequest(HistogramRequestEvent event) {
      int channel = event.getChannel();
      if (channelToHistory_.containsKey(channel)) {
         HistogramHistory history = channelToHistory_.get(channel);
         display_.postEvent(
               new NewHistogramsEvent(channel, history.datas_));
      }
   }
}