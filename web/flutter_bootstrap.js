(() => {
  if (!window._flutter) {
    window._flutter = {};
  }
  _flutter.loader.load({
    onEntrypointLoaded: function(engineInitializer) {
      engineInitializer.initializeEngine().then(function(appRunner) {
        appRunner.runApp();
      });
    }
  });
})();
