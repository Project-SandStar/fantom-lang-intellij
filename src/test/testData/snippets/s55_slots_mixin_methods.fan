mixin M
{
  abstract Bool i
  static const Int x
  static const Int y := 3
  abstract Void h()
  native Int foo()
  once Str bar() { "a" }
  virtual Void g() {}
  static Void s() {}
  Str? name;
}

enum class B
{
  simple, disabled, bigSync, bigAsync(false), bigAsync(true),
  example,
  table
  private new make(Int tagId := 0) { this.tagId = tagId }
  @Operator Month increment() { this }
  const Int tagId
}

enum class C { a, b; }
