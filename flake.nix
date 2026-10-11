{
  description = "clojurecraft: the tools the bot is built and checked with, pinned by flake.lock";

  inputs = {
    nixpkgs.url = "https://channels.nixos.org/nixpkgs-unstable/nixexprs.tar.zst";
  };

  # nix develop             a shell with every tool below; entering it installs the git hooks
  # nix build .#clj-kondo   one tool; scripts/clj, scripts/clj-kondo and scripts/clojure-lsp build
  #                         these into .nix/ links, so the hooks need no shell
  # nix flake update        move every tool to today's nixpkgs; flake.lock records the move
  outputs = inputs:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "x86_64-linux" "aarch64-linux" ];
      forEach = f: builtins.mapAttrs (system: pkgs: f pkgs)
        (inputs.nixpkgs.lib.getAttrs systems inputs.nixpkgs.legacyPackages);
      tools = pkgs: rec {
        jdk = pkgs.jdk25;                                 # the server and the bot run on Java 25
        clojure = pkgs.clojure.override { inherit jdk; }; # the Clojure CLI, on that same Java
        clj-kondo = pkgs.clj-kondo;                       # lint, in the pre-commit hook (native)
        clojure-lsp = pkgs.clojure-lsp;                   # clean-ns and unused public vars
        lefthook = pkgs.lefthook;                         # runs the git hooks in lefthook.yml
        zstd = pkgs.zstd;                                 # gym recordings are .edn.zst
      };
    in {
      packages = forEach tools;

      devShells = forEach (pkgs: {
        default = pkgs.mkShell {
          packages = builtins.attrValues (tools pkgs);
          shellHook = ''
            lefthook install >/dev/null
          '';
        };
      });
    };
}
