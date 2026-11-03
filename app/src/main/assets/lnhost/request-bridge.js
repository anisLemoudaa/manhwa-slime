(function (global) {
  const pendingRequests = new Map();

  global.__lnBeginRequest = function (requestId, pluginId, method, argsJson) {
    const request = { state: "__pending__" };
    pendingRequests.set(requestId, request);

    Promise.resolve()
      .then(function () {
        return global.__lnCallMethod(pluginId, method, argsJson);
      })
      .then(
        function (result) {
          if (pendingRequests.get(requestId) === request) request.state = result;
        },
        function (error) {
          if (pendingRequests.get(requestId) === request) {
            request.state = JSON.stringify({
              ok: false,
              error: String((error && error.message) || error),
            });
          }
        },
      );
  };

  global.__lnGetRequestState = function (requestId) {
    const request = pendingRequests.get(requestId);
    return request ? String(request.state) : "__missing__";
  };

  global.__lnForgetRequest = function (requestId) {
    pendingRequests.delete(requestId);
  };
})(globalThis);
