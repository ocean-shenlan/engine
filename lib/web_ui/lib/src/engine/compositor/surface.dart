// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

part of engine;

typedef SubmitCallback = bool Function(SurfaceFrame, SkCanvas);

/// A frame which contains a canvas to be drawn into.
class SurfaceFrame {
  final SkSurface skiaSurface;
  final SubmitCallback submitCallback;
  bool _submitted;

  SurfaceFrame(this.skiaSurface, this.submitCallback)
      : _submitted = false,
        assert(skiaSurface != null), // ignore: unnecessary_null_comparison
        assert(submitCallback != null); // ignore: unnecessary_null_comparison

  /// Submit this frame to be drawn.
  bool submit() {
    if (_submitted) {
      return false;
    }
    return submitCallback(this, skiaCanvas);
  }

  SkCanvas get skiaCanvas => skiaSurface.getCanvas();
}

/// A surface which can be drawn into by the compositor.
///
/// The underlying representation is a [SkSurface], which can be reused by
/// successive frames if they are the same size. Otherwise, a new [SkSurface] is
/// created.
class Surface {
  Surface(this.viewEmbedder);

  SkSurface? _surface;
  html.Element? htmlElement;

  bool _addedToScene = false;

  /// The default view embedder. Coordinates embedding platform views and
  /// overlaying subsequent draw operations on top.
  final HtmlViewEmbedder viewEmbedder;

  /// Acquire a frame of the given [size] containing a drawable canvas.
  ///
  /// The given [size] is in physical pixels.
  SurfaceFrame acquireFrame(ui.Size size) {
    final SkSurface surface = acquireRenderSurface(size);

    canvasKit.callMethod('setCurrentContext', <int?>[surface.context]);
    SubmitCallback submitCallback =
        (SurfaceFrame surfaceFrame, SkCanvas canvas) {
      return _presentSurface();
    };

    return SurfaceFrame(surface, submitCallback);
  }

  SkSurface acquireRenderSurface(ui.Size size) {
    _createOrUpdateSurfaces(size);
    return _surface!;
  }

  void addToScene() {
    if (!_addedToScene) {
      skiaSceneHost!.children.insert(0, htmlElement!);
    }
    _addedToScene = true;
  }

  void _createOrUpdateSurfaces(ui.Size size) {
    if (size.isEmpty) {
      throw CanvasKitError('Cannot create surfaces of empty size.');
    }

    final SkSurface? currentSurface = _surface;
    if (currentSurface != null) {
      final bool isSameSize =
        size.width == currentSurface.width() &&
        size.height == currentSurface.height();
      if (isSameSize) {
        // The existing surface is still reusable.
        return;
      }
    }

    currentSurface?.dispose();
    _surface = null;
    htmlElement?.remove();
    htmlElement = null;
    _addedToScene = false;

    _surface = _wrapHtmlCanvas(size);
  }

  SkSurface _wrapHtmlCanvas(ui.Size size) {
    final ui.Size logicalSize = size / ui.window.devicePixelRatio;
    final html.CanvasElement htmlCanvas = html.CanvasElement(
        width: size.width.ceil(), height: size.height.ceil());
    htmlCanvas.style
      ..position = 'absolute'
      ..width = '${logicalSize.width.ceil()}px'
      ..height = '${logicalSize.height.ceil()}px';
    final int glContext = canvasKit.callMethod('GetWebGLContext', <dynamic>[
      htmlCanvas,
      // Default to no anti-aliasing. Paint commands can be explicitly
      // anti-aliased by setting their `Paint` object's `antialias` property.
      js.JsObject.jsify({'antialias': 0}),
    ]);
    final js.JsObject? grContext =
        canvasKit.callMethod('MakeGrContext', <dynamic>[glContext]);

    if (grContext == null) {
      throw CanvasKitError('Could not create a graphics context.');
    }

    final js.JsObject? skSurface =
        canvasKit.callMethod('MakeOnScreenGLSurface', <dynamic>[
      grContext,
      size.width,
      size.height,
      canvasKit['SkColorSpace']['SRGB'],
    ]);

    if (skSurface == null) {
      throw CanvasKitError('Could not create a surface.');
    }

    htmlElement = htmlCanvas;
    return SkSurface(skSurface, glContext);
  }

  bool _presentSurface() {
    canvasKit.callMethod('setCurrentContext', <dynamic>[_surface!.context]);
    _surface!.getCanvas().flush();
    return true;
  }
}

/// A Dart wrapper around Skia's SkSurface.
class SkSurface {
  final js.JsObject _surface;
  final int _glContext;

  SkSurface(this._surface, this._glContext);

  SkCanvas getCanvas() {
    final js.JsObject skCanvas = _surface.callMethod('getCanvas');
    return SkCanvas(skCanvas);
  }

  int get context => _glContext;

  int width() => _surface.callMethod('width');
  int height() => _surface.callMethod('height');

  void dispose() {
    _surface.callMethod('dispose');
  }
}
