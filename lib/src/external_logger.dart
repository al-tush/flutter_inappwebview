typedef ExceptionCallback = Function(Object exception);

class ExternalLogger {
  static ExceptionCallback? _exceptionCallback;
  
  static void setExceptionCallback(ExceptionCallback? callback) {
    _exceptionCallback = callback;
  }
  
  static onException(Object exception) {
    _exceptionCallback?.call(exception);
  }
}