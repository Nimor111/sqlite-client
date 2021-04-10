{pkgs ? import <nixpkgs> {}}:

let
  scalaDeps = [
    pkgs.scala
    pkgs.sbt
  ];
in
pkgs.mkShell {
  buildInputs = [
    scalaDeps
    pkgs.jekyll
  ];
}
