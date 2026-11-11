class A
{
  Void f()
  {
    list.clear
    [LogLevel.err, LogLevel.warn].each |LogLevel l| {}
    verifyUriEq(u1, u2)
    [u1, u2].each |Uri u| {}
    reset
    (2..4).each {}
    flush
    log.err("xyz")
    verify(x)
    x := [,]
    [1, 2].each |Int i| { echo(i) }
    y := [:]
    ["f", "g", "h"].each |Str mn|
    {
      echo(mn)
    }
  }
}
