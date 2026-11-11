class A { Void f() {
    x := a ? b : c
    y := x is Str ? 1 : 2
    z := cond ? Foo.bar : Baz.qux
    [1, 2].each |Int i| {}
    [u1, u2].each |Uri u| {}
    (2..4).each {}
    w := Str ? 1 : 2
    v := list[0]
    u := map[Str#]
  } }
