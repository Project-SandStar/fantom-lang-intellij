class A
{
  Void f()
  {
    try { g } catch { }
    for (;;) { break }
    x := y ?: throw Err("x")
    z := cond ? Foo.bar : Baz.qux
    w := a ? b : c.d
    s := Str<|hello
      world|>
    r := Regex<|a+b|>
    l := "$<key>"
    return
  }
}
