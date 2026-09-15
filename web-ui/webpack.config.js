const path = require("path");
const webpack = require("webpack");

module.exports = (webpackConfigEnv = {}, argv = {}) => {
  const { mode = "development" } = argv;
  const isProduction = mode === "production";

  // Do not include these packages in the bundle as they will be provided by the gateway
  const externals = [
    "react",
    "react-dom",
  ];

  return {
    mode,
    entry: {
      LogixConnectionBrowser: path.join(__dirname, "src/index.ts"),
    },
    output: {
      library: "[name]",
      libraryTarget: "umd",
      umdNamedDefine: true,
      globalObject: 'this',
      filename: "[name].js",
      publicPath: "",
      path: path.resolve(__dirname, "build/generated-resources/mounted/"),
    },
    context: path.resolve(__dirname),
    module: {
      rules: [
        {
          test: /\.css$|.scss$/,
          use: ["style-loader", "css-loader", "sass-loader"],
        },
        {
          test: /\.[tj]sx?$/,
          use: [
            {
              loader: "ts-loader",
              options: { configFile: 'tsconfig.webpack.json' },
            },
          ],
          exclude: /node_modules/,
        },
      ],
    },
    // Disable source maps in production to avoid exposing source code
    devtool: isProduction ? false : "source-map",
    plugins: [],
    resolve: {
      modules: ["node_modules"],
      extensions: [".ts", ".tsx", ".js", ".jsx", ".scss", ".css", ".d.ts"],
    },
    externals,
  };
};
