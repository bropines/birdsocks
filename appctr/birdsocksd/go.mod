// Not a module of its own: this file only keeps birdsocksd out of the appctr
// module, which cannot import NetBird's internal packages. build.sh compiles
// it inside NetBird's tree (client/birdsocksd), without this file.
module birdsocksd
