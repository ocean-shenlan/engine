// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.android;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.embedding.engine.renderer.RenderSurface;

/**
 * Paints a Flutter UI provided by an {@link android.media.ImageReader} onto a {@link
 * android.graphics.Canvas}.
 *
 * <p>A {@code FlutterImageView} is intended for situations where a developer needs to render a
 * Flutter UI, but also needs to render an interactive {@link
 * io.flutter.plugin.platform.PlatformView}.
 *
 * <p>This {@code View} takes an {@link android.media.ImageReader} that provides the Flutter UI in
 * an {@link android.media.Image} and renders it to the {@link android.graphics.Canvas} in {@code
 * onDraw}.
 */
@SuppressLint("ViewConstructor")
@TargetApi(19)
public class FlutterImageView extends View implements RenderSurface {
  private final ImageReader imageReader;
  @Nullable private Image nextImage;
  @Nullable private Image currentImage;
  @Nullable private Bitmap currentBitmap;
  @Nullable private FlutterRenderer flutterRenderer;

  public enum SurfaceKind {
    /** Displays the background canvas. */
    background,

    /** Displays the overlay surface canvas. */
    overlay,
  }

  /** The kind of surface. */
  private SurfaceKind kind;

  /**
   * The number of images acquired from the current {@link android.media.ImageReader} that are
   * waiting to be painted. This counter is decreased after calling {@link
   * android.media.Image#close()}.
   */
  private int pendingImages = 0;

  /**
   * Constructs a {@code FlutterImageView} with an {@link android.media.ImageReader} that provides
   * the Flutter UI.
   */
  public FlutterImageView(
      @NonNull Context context, @NonNull ImageReader imageReader, SurfaceKind kind) {
    super(context, null);
    this.imageReader = imageReader;
    this.kind = kind;
  }

  @Nullable
  @Override
  public FlutterRenderer getAttachedRenderer() {
    return flutterRenderer;
  }

  /**
   * Invoked by the owner of this {@code FlutterImageView} when it wants to begin rendering a
   * Flutter UI to this {@code FlutterImageView}.
   */
  @Override
  public void attachToRenderer(@NonNull FlutterRenderer flutterRenderer) {
    this.flutterRenderer = flutterRenderer;
    switch (kind) {
      case background:
        flutterRenderer.swapSurface(imageReader.getSurface());
        break;
      case overlay:
        // Don't do anything as this is done by the handler of
        // `FlutterJNI#createOverlaySurface()` in the native side.
        break;
    }
  }

  /**
   * Invoked by the owner of this {@code FlutterImageView} when it no longer wants to render a
   * Flutter UI to this {@code FlutterImageView}.
   */
  public void detachFromRenderer() {
    switch (kind) {
      case background:
        // TODO: Swap the surface back to the original one.
        // https://github.com/flutter/flutter/issues/58291
        break;
      case overlay:
        // TODO: Handle this in the native side.
        // https://github.com/flutter/flutter/issues/59904
        break;
    }
  }

  public void pause() {
    // Not supported.
  }

  /** Acquires the next image to be drawn to the {@link android.graphics.Canvas}. */
  @TargetApi(19)
  public void acquireLatestImage() {
    // There's no guarantee that the image will be closed before the next call to
    // `acquireLatestImage()`. For example, the device may not produce new frames if
    // it's in sleep mode, so the calls to `invalidate()` will be queued up
    // until the device produces a new frame.
    //
    // While the engine will also stop producing frames, there is a race condition.
    //
    // To avoid exceptions, check if a new image can be acquired.
    if (pendingImages < imageReader.getMaxImages()) {
      nextImage = imageReader.acquireLatestImage();
      if (nextImage != null) {
        pendingImages++;
      }
    }
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (nextImage != null) {
      if (currentImage != null) {
        currentImage.close();
        pendingImages--;
      }
      currentImage = nextImage;
      nextImage = null;
      updateCurrentBitmap();
    }

    if (currentBitmap != null) {
      canvas.drawBitmap(currentBitmap, 0, 0, null);
    }
  }

  @TargetApi(29)
  private void updateCurrentBitmap() {
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      final HardwareBuffer buffer = currentImage.getHardwareBuffer();
      currentBitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
    } else {
      final Plane[] imagePlanes = currentImage.getPlanes();
      if (imagePlanes.length != 1) {
        return;
      }

      final Plane imagePlane = imagePlanes[0];
      final int desiredWidth = imagePlane.getRowStride() / imagePlane.getPixelStride();
      final int desiredHeight = currentImage.getHeight();

      if (currentBitmap == null
          || currentBitmap.getWidth() != desiredWidth
          || currentBitmap.getHeight() != desiredHeight) {
        currentBitmap =
            Bitmap.createBitmap(
                desiredWidth, desiredHeight, android.graphics.Bitmap.Config.ARGB_8888);
      }

      currentBitmap.copyPixelsFromBuffer(imagePlane.getBuffer());
    }
  }
}
