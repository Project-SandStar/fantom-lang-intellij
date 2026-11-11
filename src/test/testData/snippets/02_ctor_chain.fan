class Foo : Bar
{
  new make() : super("div") {}
  protected new makeX() : this.make() {}
  static new fromStr(Str s) { make() }
}
